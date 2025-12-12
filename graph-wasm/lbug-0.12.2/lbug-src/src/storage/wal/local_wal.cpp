#include "storage/wal/local_wal.h"

#include "binder/ddl/bound_alter_info.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "common/serializer/in_mem_file_writer.h"
#include "common/vector/value_vector.h"
#include "storage/wal/checksum_writer.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace storage {

LocalWAL::LocalWAL(MemoryManager& mm, bool enableChecksums)
    : inMemWriter(std::make_shared<InMemFileWriter>(mm)),
      serializer(enableChecksums ? std::make_shared<ChecksumWriter>(inMemWriter, mm) :
                                   std::static_pointer_cast<Writer>(inMemWriter)) {}

void LocalWAL::logBeginTransaction() {
    BeginTransactionRecord walRecord;
    addNewWALRecord(walRecord);
}

void LocalWAL::logCommit() {
    CommitRecord walRecord;
    addNewWALRecord(walRecord);
}

void LocalWAL::logCreateCatalogEntryRecord(CatalogEntry* catalogEntry, bool isInternal) {
    CreateCatalogEntryRecord walRecord(catalogEntry, isInternal);
    addNewWALRecord(walRecord);
}

void LocalWAL::logDropCatalogEntryRecord(table_id_t tableID, CatalogEntryType type) {
    DropCatalogEntryRecord walRecord(tableID, type);
    addNewWALRecord(walRecord);
}

void LocalWAL::logAlterCatalogEntryRecord(const BoundAlterInfo* alterInfo) {
    AlterTableEntryRecord walRecord(alterInfo);
    addNewWALRecord(walRecord);
}

void LocalWAL::logTableInsertion(table_id_t tableID, TableType tableType, row_idx_t numRows,
    const std::vector<ValueVector*>& vectors) {
    TableInsertionRecord walRecord(tableID, tableType, numRows, vectors);
    addNewWALRecord(walRecord);
}

void LocalWAL::logNodeDeletion(table_id_t tableID, offset_t nodeOffset, ValueVector* pkVector) {
    NodeDeletionRecord walRecord(tableID, nodeOffset, pkVector);
    addNewWALRecord(walRecord);
}

void LocalWAL::logNodeUpdate(table_id_t tableID, column_id_t columnID, offset_t nodeOffset,
    ValueVector* propertyVector) {
    NodeUpdateRecord walRecord(tableID, columnID, nodeOffset, propertyVector);
    addNewWALRecord(walRecord);
}

void LocalWAL::logRelDelete(table_id_t tableID, ValueVector* srcNodeVector,
    ValueVector* dstNodeVector, ValueVector* relIDVector) {
    RelDeletionRecord walRecord(tableID, srcNodeVector, dstNodeVector, relIDVector);
    addNewWALRecord(walRecord);
}

void LocalWAL::logRelDetachDelete(table_id_t tableID, RelDataDirection direction,
    ValueVector* srcNodeVector) {
    RelDetachDeleteRecord walRecord(tableID, direction, srcNodeVector);
    addNewWALRecord(walRecord);
}

void LocalWAL::logRelUpdate(table_id_t tableID, column_id_t columnID, ValueVector* srcNodeVector,
    ValueVector* dstNodeVector, ValueVector* relIDVector, ValueVector* propertyVector) {
    RelUpdateRecord walRecord(tableID, columnID, srcNodeVector, dstNodeVector, relIDVector,
        propertyVector);
    addNewWALRecord(walRecord);
}

void LocalWAL::logUpdateSequenceRecord(sequence_id_t sequenceID, uint64_t kCount) {
    UpdateSequenceRecord walRecord(sequenceID, kCount);
    addNewWALRecord(walRecord);
}

void LocalWAL::logLoadExtension(std::string path) {
    LoadExtensionRecord walRecord(std::move(path));
    addNewWALRecord(walRecord);
}

// NOLINTNEXTLINE(readability-make-member-function-const): semantically non-const function.
void LocalWAL::clear() {
    std::unique_lock lck{mtx};
    serializer.getWriter()->clear();
}

uint64_t LocalWAL::getSize() {
    std::unique_lock lck{mtx};
    return serializer.getWriter()->getSize();
}

// NOLINTNEXTLINE(readability-make-member-function-const): semantically non-const function.
void LocalWAL::addNewWALRecord(const WALRecord& walRecord) {
    std::unique_lock lck{mtx};
    KU_ASSERT(walRecord.type != WALRecordType::INVALID_RECORD);
    serializer.getWriter()->onObjectBegin();
    walRecord.serialize(serializer);
    serializer.getWriter()->onObjectEnd();
}

} // namespace storage
} // namespace lbug
