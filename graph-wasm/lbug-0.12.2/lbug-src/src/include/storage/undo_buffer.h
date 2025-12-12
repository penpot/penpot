#pragma once

#include <mutex>

#include "buffer_manager/memory_manager.h"
#include "common/types/types.h"

namespace lbug {
namespace catalog {
class CatalogEntry;
class CatalogSet;
class SequenceCatalogEntry;
struct SequenceRollbackData;
} // namespace catalog
namespace transaction {
class Transaction;
}

namespace main {
class ClientContext;
}
namespace storage {
class VersionRecordHandler;

class UndoMemoryBuffer {
public:
    static constexpr uint64_t UNDO_MEMORY_BUFFER_INIT_CAPACITY = common::LBUG_PAGE_SIZE;

    explicit UndoMemoryBuffer(std::unique_ptr<MemoryBuffer> buffer, uint64_t capacity)
        : buffer{std::move(buffer)}, capacity{capacity} {
        currentPosition = 0;
    }

    uint8_t* getDataUnsafe() const { return buffer->getData(); }
    uint8_t const* getData() const { return buffer->getData(); }
    uint64_t getSize() const { return capacity; }
    uint64_t getCurrentPosition() const { return currentPosition; }
    void moveCurrentPosition(uint64_t offset) {
        KU_ASSERT(currentPosition + offset <= capacity);
        currentPosition += offset;
    }
    bool canFit(uint64_t size_) const { return currentPosition + size_ <= this->capacity; }

private:
    std::unique_ptr<MemoryBuffer> buffer;
    uint64_t capacity;
    uint64_t currentPosition;
};

class UndoBuffer;
class UndoBufferIterator {
public:
    explicit UndoBufferIterator(const UndoBuffer& undoBuffer) : undoBuffer{undoBuffer} {}

    template<typename F>
    void iterate(F&& callback);
    template<typename F>
    void reverseIterate(F&& callback);

private:
    const UndoBuffer& undoBuffer;
};

class UpdateInfo;
class VersionInfo;
struct VectorUpdateInfo;
class WAL;
// This class is not thread safe, as it is supposed to be accessed by a single thread.
class UndoBuffer {
    friend class UndoBufferIterator;

public:
    enum class UndoRecordType : uint16_t {
        CATALOG_ENTRY = 0,
        SEQUENCE_ENTRY = 1,
        UPDATE_INFO = 6,
        INSERT_INFO = 7,
        DELETE_INFO = 8,
    };

    explicit UndoBuffer(MemoryManager* mm) : mm{mm} {}

    void createCatalogEntry(catalog::CatalogSet& catalogSet, catalog::CatalogEntry& catalogEntry);
    void createSequenceChange(catalog::SequenceCatalogEntry& sequenceEntry,
        const catalog::SequenceRollbackData& data);
    void createInsertInfo(common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
        common::row_idx_t numRows, const VersionRecordHandler* versionRecordHandler);
    void createDeleteInfo(common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
        common::row_idx_t numRows, const VersionRecordHandler* versionRecordHandler);
    void createVectorUpdateInfo(UpdateInfo* updateInfo, common::idx_t vectorIdx,
        VectorUpdateInfo* vectorUpdateInfo, common::transaction_t version);

    void commit(common::transaction_t commitTS) const;
    void rollback(main::ClientContext* context) const;

private:
    uint8_t* createUndoRecord(uint64_t size);

    void createVersionInfo(UndoRecordType recordType, common::row_idx_t startRow,
        common::row_idx_t numRows, const VersionRecordHandler* versionRecordHandler,
        common::node_group_idx_t nodeGroupIdx = 0);

    static void commitRecord(UndoRecordType recordType, const uint8_t* record,
        common::transaction_t commitTS);
    static void rollbackRecord(main::ClientContext* context, UndoRecordType recordType,
        const uint8_t* record);

    static void commitCatalogEntryRecord(const uint8_t* record, common::transaction_t commitTS);
    static void rollbackCatalogEntryRecord(const uint8_t* record);

    static void commitSequenceEntry(uint8_t const* entry, common::transaction_t commitTS);
    static void rollbackSequenceEntry(uint8_t const* entry);

    static void commitVersionInfo(UndoRecordType recordType, const uint8_t* record,
        common::transaction_t commitTS);
    static void rollbackVersionInfo(main::ClientContext* context, UndoRecordType recordType,
        const uint8_t* record);

    static void commitVectorUpdateInfo(const uint8_t* record, common::transaction_t commitTS);
    static void rollbackVectorUpdateInfo(const uint8_t* record);

private:
    std::mutex mtx;
    MemoryManager* mm;
    std::vector<UndoMemoryBuffer> memoryBuffers;
};

} // namespace storage
} // namespace lbug
