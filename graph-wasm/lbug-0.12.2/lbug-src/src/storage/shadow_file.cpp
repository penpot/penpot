#include "storage/shadow_file.h"

#include "common/exception/io.h"
#include "common/file_system/virtual_file_system.h"
#include "common/serializer/buffered_file.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/database_header.h"
#include "storage/file_db_id_utils.h"
#include "storage/file_handle.h"
#include "storage/storage_manager.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace storage {

void ShadowPageRecord::serialize(Serializer& serializer) const {
    serializer.write<file_idx_t>(originalFileIdx);
    serializer.write<page_idx_t>(originalPageIdx);
}

ShadowPageRecord ShadowPageRecord::deserialize(Deserializer& deserializer) {
    file_idx_t originalFileIdx = INVALID_FILE_IDX;
    page_idx_t originalPageIdx = INVALID_PAGE_IDX;
    deserializer.deserializeValue<file_idx_t>(originalFileIdx);
    deserializer.deserializeValue<page_idx_t>(originalPageIdx);
    return ShadowPageRecord{originalFileIdx, originalPageIdx};
}

ShadowFile::ShadowFile(BufferManager& bm, VirtualFileSystem* vfs, const std::string& databasePath)
    : bm{bm}, shadowFilePath{StorageUtils::getShadowFilePath(databasePath)}, vfs{vfs},
      shadowingFH{nullptr} {
    KU_ASSERT(vfs);
}

void ShadowFile::clearShadowPage(file_idx_t originalFile, page_idx_t originalPage) {
    if (hasShadowPage(originalFile, originalPage)) {
        shadowPagesMap.at(originalFile).erase(originalPage);
        if (shadowPagesMap.at(originalFile).empty()) {
            shadowPagesMap.erase(originalFile);
        }
    }
}

page_idx_t ShadowFile::getOrCreateShadowPage(file_idx_t originalFile, page_idx_t originalPage) {
    if (hasShadowPage(originalFile, originalPage)) {
        return shadowPagesMap[originalFile][originalPage];
    }
    const auto shadowPageIdx = getOrCreateShadowingFH()->addNewPage();
    shadowPagesMap[originalFile][originalPage] = shadowPageIdx;
    shadowPageRecords.push_back({originalFile, originalPage});
    return shadowPageIdx;
}

page_idx_t ShadowFile::getShadowPage(file_idx_t originalFile, page_idx_t originalPage) const {
    KU_ASSERT(hasShadowPage(originalFile, originalPage));
    return shadowPagesMap.at(originalFile).at(originalPage);
}

void ShadowFile::applyShadowPages(ClientContext& context) const {
    const auto pageBuffer = std::make_unique<uint8_t[]>(LBUG_PAGE_SIZE);
    page_idx_t shadowPageIdx = 1; // Skip header page.
    auto dataFileInfo = StorageManager::Get(context)->getDataFH()->getFileInfo();
    KU_ASSERT(shadowingFH);
    for (const auto& record : shadowPageRecords) {
        shadowingFH->readPageFromDisk(pageBuffer.get(), shadowPageIdx++);
        dataFileInfo->writeFile(pageBuffer.get(), LBUG_PAGE_SIZE,
            record.originalPageIdx * LBUG_PAGE_SIZE);
        // NOTE: We're not taking lock here, as we assume this is only called with a single thread.
        MemoryManager::Get(context)->getBufferManager()->updateFrameIfPageIsInFrameWithoutLock(
            record.originalFileIdx, pageBuffer.get(), record.originalPageIdx);
    }
    dataFileInfo->syncFile();
}

static ku_uuid_t getOldDatabaseID(FileInfo& dataFileInfo) {
    auto oldHeader = DatabaseHeader::readDatabaseHeader(dataFileInfo);
    if (!oldHeader.has_value()) {
        throw InternalException("Found a shadow file for database {} but no valid database header. "
                                "The database is corrupted, please recreate it.");
    }
    return oldHeader->databaseID;
}

void ShadowFile::replayShadowPageRecords(ClientContext& context) {
    if (context.getDBConfig()->readOnly) {
        throw RuntimeException("Couldn't replay shadow pages under read-only mode. Please re-open "
                               "the database with read-write mode to replay shadow pages.");
    }
    auto vfs = VirtualFileSystem::GetUnsafe(context);
    auto shadowFilePath = StorageUtils::getShadowFilePath(context.getDatabasePath());
    auto shadowFileInfo = vfs->openFile(shadowFilePath, FileOpenFlags(FileFlags::READ_ONLY));

    std::unique_ptr<FileInfo> dataFileInfo;
    try {
        dataFileInfo = vfs->openFile(context.getDatabasePath(),
            FileOpenFlags{FileFlags::WRITE | FileFlags::READ_ONLY, FileLockType::WRITE_LOCK});
    } catch (IOException& e) {
        throw RuntimeException(stringFormat(
            "Found shadow file {} but no corresponding database file. This file "
            "may have been left behind from a previous database with the same name. If it is safe "
            "to do so, please delete this file and restart the database.",
            shadowFilePath));
    }

    ShadowFileHeader header;
    const auto headerBuffer = std::make_unique<uint8_t[]>(LBUG_PAGE_SIZE);
    shadowFileInfo->readFromFile(headerBuffer.get(), LBUG_PAGE_SIZE, 0);
    memcpy(&header, headerBuffer.get(), sizeof(ShadowFileHeader));

    // When replaying the shadow file we haven't read the database ID from the database
    // header yet
    // So we need to do it separately here to verify the shadow file matches the database
    auto oldDatabaseID = getOldDatabaseID(*dataFileInfo);
    FileDBIDUtils::verifyDatabaseID(*shadowFileInfo, oldDatabaseID, header.databaseID);

    std::vector<ShadowPageRecord> shadowPageRecords;
    shadowPageRecords.reserve(header.numShadowPages);
    auto reader = std::make_unique<BufferedFileReader>(*shadowFileInfo);
    reader->resetReadOffset((header.numShadowPages + 1) * LBUG_PAGE_SIZE);
    Deserializer deSer(std::move(reader));
    deSer.deserializeVector(shadowPageRecords);

    const auto pageBuffer = std::make_unique<uint8_t[]>(LBUG_PAGE_SIZE);
    page_idx_t shadowPageIdx = 1;
    for (const auto& record : shadowPageRecords) {
        shadowFileInfo->readFromFile(pageBuffer.get(), LBUG_PAGE_SIZE,
            shadowPageIdx * LBUG_PAGE_SIZE);
        dataFileInfo->writeFile(pageBuffer.get(), LBUG_PAGE_SIZE,
            record.originalPageIdx * LBUG_PAGE_SIZE);
        shadowPageIdx++;
    }
}

void ShadowFile::flushAll(main::ClientContext& context) const {
    // Write header page to file.
    ShadowFileHeader header;
    header.numShadowPages = shadowPageRecords.size();
    header.databaseID = StorageManager::Get(context)->getOrInitDatabaseID(context);
    const auto headerBuffer = std::make_unique<uint8_t[]>(LBUG_PAGE_SIZE);
    memcpy(headerBuffer.get(), &header, sizeof(ShadowFileHeader));
    KU_ASSERT(shadowingFH && !shadowingFH->isInMemoryMode());
    shadowingFH->writePageToFile(headerBuffer.get(), 0);
    // Flush shadow pages to file.
    shadowingFH->flushAllDirtyPagesInFrames();
    // Append shadow page records to the end of the file.
    const auto writer = std::make_shared<BufferedFileWriter>(*shadowingFH->getFileInfo());
    writer->setFileOffset(shadowingFH->getNumPages() * LBUG_PAGE_SIZE);
    Serializer ser(writer);
    KU_ASSERT(shadowPageRecords.size() + 1 == shadowingFH->getNumPages());
    ser.serializeVector(shadowPageRecords);
    writer->flush();
    // Sync the file to disk.
    writer->sync();
}

void ShadowFile::clear(BufferManager& bm) {
    KU_ASSERT(shadowingFH);
    // TODO(Guodong): We should remove shadow file here. This requires changes:
    // 1. We need to make shadow file not going through BM.
    // 2. We need to remove fileHandles held in BM, so that BM only keeps FH for the data file.
    bm.removeFilePagesFromFrames(*shadowingFH);
    shadowingFH->resetToZeroPagesAndPageCapacity();
    shadowPagesMap.clear();
    shadowPageRecords.clear();
    // Reserve header page.
    shadowingFH->addNewPage();
}

void ShadowFile::reset() {
    shadowingFH->resetFileInfo();
    shadowingFH = nullptr;
    vfs->removeFileIfExists(shadowFilePath);
}

FileHandle* ShadowFile::getOrCreateShadowingFH() {
    if (!shadowingFH) {
        shadowingFH = bm.getFileHandle(shadowFilePath,
            FileHandle::O_PERSISTENT_FILE_CREATE_NOT_EXISTS, vfs, nullptr);
        if (shadowingFH->getNumPages() == 0) {
            // Reserve the first page for the header.
            shadowingFH->addNewPage();
        }
    }
    return shadowingFH;
}

} // namespace storage
} // namespace lbug
