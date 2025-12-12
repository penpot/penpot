#include "storage/buffer_manager/spiller.h"

#include <mutex>

#include "common/assert.h"
#include "common/exception/io.h"
#include "common/file_system/virtual_file_system.h"
#include "common/types/types.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/file_handle.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace storage {

Spiller::Spiller(std::string tmpFilePath, BufferManager& bufferManager,
    common::VirtualFileSystem* vfs)
    : tmpFilePath{std::move(tmpFilePath)}, bufferManager{bufferManager}, vfs{vfs}, dataFH{nullptr} {
    // Clear the file if it already existed (e.g. from a previous run which
    // failed to clean up).
    vfs->removeFileIfExists(this->tmpFilePath);
}

FileHandle* Spiller::getOrCreateDataFH() const {
    if (dataFH.load()) {
        return dataFH;
    }
    std::unique_lock lock(fileCreationMutex);
    // Another thread may have created the file while the lock was being acquired
    if (dataFH.load()) {
        return dataFH;
    }
    const_cast<Spiller*>(this)->dataFH = bufferManager.getFileHandle(tmpFilePath,
        FileHandle::O_PERSISTENT_FILE_CREATE_NOT_EXISTS, vfs, nullptr);
    return dataFH;
}

FileHandle* Spiller::getDataFH() const {
    if (dataFH.load()) {
        return dataFH;
    }
    return nullptr;
}

void Spiller::addUnusedChunk(InMemChunkedNodeGroup* nodeGroup) {
    std::unique_lock lock(partitionerGroupsMtx);
    fullPartitionerGroups.insert(nodeGroup);
}

void Spiller::clearUnusedChunk(InMemChunkedNodeGroup* nodeGroup) {
    std::unique_lock lock(partitionerGroupsMtx);
    auto entry = fullPartitionerGroups.find(nodeGroup);
    if (entry != fullPartitionerGroups.end()) {
        fullPartitionerGroups.erase(entry);
    }
}

Spiller::~Spiller() {
    // This should be safe as long as the VFS is always using a local file system and the VFS is
    // destroyed after the buffer manager
    try {
        vfs->removeFileIfExists(this->tmpFilePath);
    } catch (common::IOException&) {} // NOLINT
}

SpillResult Spiller::spillToDisk(ColumnChunkData& chunk) const {
    auto& buffer = *chunk.buffer;
    KU_ASSERT(!buffer.evicted);
    auto dataFH = getOrCreateDataFH();
    auto pageSize = dataFH->getPageSize();
    auto numPages = (buffer.buffer.size_bytes() + pageSize - 1) / pageSize;
    auto startPage = dataFH->addNewPages(numPages);
    dataFH->writePagesToFile(buffer.buffer.data(), buffer.buffer.size_bytes(), startPage);
    return buffer.setSpilledToDisk(startPage * pageSize);
}

void Spiller::loadFromDisk(ColumnChunkData& chunk) const {
    auto& buffer = *chunk.buffer;
    if (buffer.evicted) {
        buffer.prepareLoadFromDisk();
        auto dataFH = getDataFH();
        KU_ASSERT(dataFH);
        dataFH->getFileInfo()->readFromFile(buffer.buffer.data(), buffer.buffer.size(),
            buffer.filePosition);
    }
}

SpillResult Spiller::claimNextGroup() {
    InMemChunkedNodeGroup* groupToFlush = nullptr;
    {
        std::unique_lock lock(partitionerGroupsMtx);
        if (!fullPartitionerGroups.empty()) {
            auto groupToFlushEntry = fullPartitionerGroups.begin();
            groupToFlush = *groupToFlushEntry;
            fullPartitionerGroups.erase(groupToFlushEntry);
        }
    }
    if (groupToFlush == nullptr) {
        return SpillResult{};
    }
    return groupToFlush->spillToDisk();
}

// NOLINTNEXTLINE(readability-make-member-function-const): Function shouldn't be re-ordered
void Spiller::clearFile() {
    auto curDataFH = getDataFH();
    if (curDataFH) {
        curDataFH->getFileInfo()->truncate(0);
    }
}

} // namespace storage
} // namespace lbug
