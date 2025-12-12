#pragma once

#include "storage/wal/wal_record.h"

namespace lbug {
namespace binder {
struct BoundAlterInfo;
} // namespace binder
namespace common {
class InMemFileWriter;
class ValueVector;
} // namespace common
namespace catalog {
class CatalogEntry;
} // namespace catalog

namespace storage {
class WAL;
class LocalWAL {
    friend class WAL;

public:
    explicit LocalWAL(MemoryManager& mm, bool enableChecksums);

    void logCreateCatalogEntryRecord(catalog::CatalogEntry* catalogEntry, bool isInternal);
    void logDropCatalogEntryRecord(uint64_t tableID, catalog::CatalogEntryType type);
    void logAlterCatalogEntryRecord(const binder::BoundAlterInfo* alterInfo);
    void logUpdateSequenceRecord(common::sequence_id_t sequenceID, uint64_t kCount);

    void logTableInsertion(common::table_id_t tableID, common::TableType tableType,
        common::row_idx_t numRows, const std::vector<common::ValueVector*>& vectors);
    void logNodeDeletion(common::table_id_t tableID, common::offset_t nodeOffset,
        common::ValueVector* pkVector);
    void logNodeUpdate(common::table_id_t tableID, common::column_id_t columnID,
        common::offset_t nodeOffset, common::ValueVector* propertyVector);
    void logRelDelete(common::table_id_t tableID, common::ValueVector* srcNodeVector,
        common::ValueVector* dstNodeVector, common::ValueVector* relIDVector);
    void logRelDetachDelete(common::table_id_t tableID, common::RelDataDirection direction,
        common::ValueVector* srcNodeVector);
    void logRelUpdate(common::table_id_t tableID, common::column_id_t columnID,
        common::ValueVector* srcNodeVector, common::ValueVector* dstNodeVector,
        common::ValueVector* relIDVector, common::ValueVector* propertyVector);

    void logLoadExtension(std::string path);

    void logBeginTransaction();
    void logCommit();

    void clear();
    uint64_t getSize();

private:
    void addNewWALRecord(const WALRecord& walRecord);

private:
    std::mutex mtx;
    std::shared_ptr<common::InMemFileWriter> inMemWriter;
    common::Serializer serializer;
};

} // namespace storage
} // namespace lbug
