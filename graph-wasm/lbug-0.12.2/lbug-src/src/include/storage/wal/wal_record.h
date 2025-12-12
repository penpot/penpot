#pragma once

#include <cstdint>

#include "binder/ddl/bound_alter_info.h"
#include "catalog/catalog_entry/catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "common/enums/rel_direction.h"
#include "common/enums/table_type.h"
#include "common/types/uuid.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {
class Serializer;
class Deserializer;
} // namespace common

namespace storage {

enum class WALRecordType : uint8_t {
    INVALID_RECORD = 0, // This is not used for any record. 0 is reserved to detect cases where we
                        // accidentally read from an empty buffer.
    BEGIN_TRANSACTION_RECORD = 1,
    COMMIT_RECORD = 2,

    COPY_TABLE_RECORD = 13,
    CREATE_CATALOG_ENTRY_RECORD = 14,
    DROP_CATALOG_ENTRY_RECORD = 16,
    ALTER_TABLE_ENTRY_RECORD = 17,
    UPDATE_SEQUENCE_RECORD = 18,
    TABLE_INSERTION_RECORD = 30,
    NODE_DELETION_RECORD = 31,
    NODE_UPDATE_RECORD = 32,
    REL_DELETION_RECORD = 33,
    REL_DETACH_DELETE_RECORD = 34,
    REL_UPDATE_RECORD = 35,

    LOAD_EXTENSION_RECORD = 100,

    CHECKPOINT_RECORD = 254,
};

struct WALHeader {
    common::ku_uuid_t databaseID;
    bool enableChecksums;
};

struct WALRecord {
    WALRecordType type = WALRecordType::INVALID_RECORD;

    WALRecord() = default;
    explicit WALRecord(WALRecordType type) : type{type} {}
    virtual ~WALRecord() = default;
    DELETE_COPY_DEFAULT_MOVE(WALRecord);

    virtual void serialize(common::Serializer& serializer) const;
    static std::unique_ptr<WALRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
};

struct BeginTransactionRecord final : WALRecord {
    BeginTransactionRecord() : WALRecord{WALRecordType::BEGIN_TRANSACTION_RECORD} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<BeginTransactionRecord> deserialize(common::Deserializer& deserializer);
};

struct CommitRecord final : WALRecord {
    CommitRecord() : WALRecord{WALRecordType::COMMIT_RECORD} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<CommitRecord> deserialize(common::Deserializer& deserializer);
};

struct CheckpointRecord final : WALRecord {
    CheckpointRecord() : WALRecord{WALRecordType::CHECKPOINT_RECORD} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<CheckpointRecord> deserialize(common::Deserializer& deserializer);
};

struct CreateCatalogEntryRecord final : WALRecord {
    catalog::CatalogEntry* catalogEntry;
    std::unique_ptr<catalog::CatalogEntry> ownedCatalogEntry;
    bool isInternal = false;

    CreateCatalogEntryRecord()
        : WALRecord{WALRecordType::CREATE_CATALOG_ENTRY_RECORD}, catalogEntry{nullptr} {}
    CreateCatalogEntryRecord(catalog::CatalogEntry* catalogEntry, bool isInternal)
        : WALRecord{WALRecordType::CREATE_CATALOG_ENTRY_RECORD}, catalogEntry{catalogEntry},
          isInternal{isInternal} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<CreateCatalogEntryRecord> deserialize(
        common::Deserializer& deserializer);
};

struct CopyTableRecord final : WALRecord {
    common::table_id_t tableID;

    CopyTableRecord()
        : WALRecord{WALRecordType::COPY_TABLE_RECORD}, tableID{common::INVALID_TABLE_ID} {}
    explicit CopyTableRecord(common::table_id_t tableID)
        : WALRecord{WALRecordType::COPY_TABLE_RECORD}, tableID{tableID} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<CopyTableRecord> deserialize(common::Deserializer& deserializer);
};

struct DropCatalogEntryRecord final : WALRecord {
    common::oid_t entryID;
    catalog::CatalogEntryType entryType;

    DropCatalogEntryRecord()
        : WALRecord{WALRecordType::DROP_CATALOG_ENTRY_RECORD}, entryID{common::INVALID_OID},
          entryType{} {}
    DropCatalogEntryRecord(common::table_id_t entryID, catalog::CatalogEntryType entryType)
        : WALRecord{WALRecordType::DROP_CATALOG_ENTRY_RECORD}, entryID{entryID},
          entryType{entryType} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<DropCatalogEntryRecord> deserialize(common::Deserializer& deserializer);
};

struct AlterTableEntryRecord final : WALRecord {
    const binder::BoundAlterInfo* alterInfo;
    std::unique_ptr<binder::BoundAlterInfo> ownedAlterInfo;

    AlterTableEntryRecord()
        : WALRecord{WALRecordType::ALTER_TABLE_ENTRY_RECORD}, alterInfo{nullptr} {}
    explicit AlterTableEntryRecord(const binder::BoundAlterInfo* alterInfo)
        : WALRecord{WALRecordType::ALTER_TABLE_ENTRY_RECORD}, alterInfo{alterInfo} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<AlterTableEntryRecord> deserialize(common::Deserializer& deserializer);
};

struct UpdateSequenceRecord final : WALRecord {
    common::sequence_id_t sequenceID;
    uint64_t kCount;

    UpdateSequenceRecord()
        : WALRecord{WALRecordType::UPDATE_SEQUENCE_RECORD}, sequenceID{0}, kCount{0} {}
    UpdateSequenceRecord(common::sequence_id_t sequenceID, uint64_t kCount)
        : WALRecord{WALRecordType::UPDATE_SEQUENCE_RECORD}, sequenceID{sequenceID}, kCount{kCount} {
    }

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<UpdateSequenceRecord> deserialize(common::Deserializer& deserializer);
};

struct TableInsertionRecord final : WALRecord {
    common::table_id_t tableID;
    common::TableType tableType;
    common::row_idx_t numRows;
    std::vector<common::ValueVector*> vectors;
    std::vector<std::unique_ptr<common::ValueVector>> ownedVectors;

    TableInsertionRecord()
        : WALRecord{WALRecordType::TABLE_INSERTION_RECORD}, tableID{common::INVALID_TABLE_ID},
          tableType{common::TableType::UNKNOWN}, numRows{0} {}
    TableInsertionRecord(common::table_id_t tableID, common::TableType tableType,
        common::row_idx_t numRows, const std::vector<common::ValueVector*>& vectors)
        : WALRecord{WALRecordType::TABLE_INSERTION_RECORD}, tableID{tableID}, tableType{tableType},
          numRows{numRows}, vectors{vectors} {}
    TableInsertionRecord(common::table_id_t tableID, common::TableType tableType,
        common::row_idx_t numRows, std::vector<std::unique_ptr<common::ValueVector>> vectors)
        : WALRecord{WALRecordType::TABLE_INSERTION_RECORD}, tableID{tableID}, tableType{tableType},
          numRows{numRows}, ownedVectors{std::move(vectors)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<TableInsertionRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct NodeDeletionRecord final : WALRecord {
    common::table_id_t tableID;
    common::offset_t nodeOffset;
    common::ValueVector* pkVector;
    std::unique_ptr<common::ValueVector> ownedPKVector;

    NodeDeletionRecord()
        : WALRecord{WALRecordType::NODE_DELETION_RECORD}, tableID{common::INVALID_TABLE_ID},
          nodeOffset{common::INVALID_OFFSET}, pkVector{nullptr} {}
    NodeDeletionRecord(common::table_id_t tableID, common::offset_t nodeOffset,
        common::ValueVector* pkVector)
        : WALRecord{WALRecordType::NODE_DELETION_RECORD}, tableID{tableID}, nodeOffset{nodeOffset},
          pkVector{pkVector} {}
    NodeDeletionRecord(common::table_id_t tableID, common::offset_t nodeOffset,
        std::unique_ptr<common::ValueVector> pkVector)
        : WALRecord{WALRecordType::NODE_DELETION_RECORD}, tableID{tableID}, nodeOffset{nodeOffset},
          pkVector{nullptr}, ownedPKVector{std::move(pkVector)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<NodeDeletionRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct NodeUpdateRecord final : WALRecord {
    common::table_id_t tableID;
    common::column_id_t columnID;
    common::offset_t nodeOffset;
    common::ValueVector* propertyVector;
    std::unique_ptr<common::ValueVector> ownedPropertyVector;

    NodeUpdateRecord()
        : WALRecord{WALRecordType::NODE_UPDATE_RECORD}, tableID{common::INVALID_TABLE_ID},
          columnID{common::INVALID_COLUMN_ID}, nodeOffset{common::INVALID_OFFSET},
          propertyVector{nullptr} {}
    NodeUpdateRecord(common::table_id_t tableID, common::column_id_t columnID,
        common::offset_t nodeOffset, common::ValueVector* propertyVector)
        : WALRecord{WALRecordType::NODE_UPDATE_RECORD}, tableID{tableID}, columnID{columnID},
          nodeOffset{nodeOffset}, propertyVector{propertyVector} {}
    NodeUpdateRecord(common::table_id_t tableID, common::column_id_t columnID,
        common::offset_t nodeOffset, std::unique_ptr<common::ValueVector> propertyVector)
        : WALRecord{WALRecordType::NODE_UPDATE_RECORD}, tableID{tableID}, columnID{columnID},
          nodeOffset{nodeOffset}, propertyVector{nullptr},
          ownedPropertyVector{std::move(propertyVector)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<NodeUpdateRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct RelDeletionRecord final : WALRecord {
    common::table_id_t tableID;
    common::ValueVector* srcNodeIDVector;
    common::ValueVector* dstNodeIDVector;
    common::ValueVector* relIDVector;
    std::unique_ptr<common::ValueVector> ownedSrcNodeIDVector;
    std::unique_ptr<common::ValueVector> ownedDstNodeIDVector;
    std::unique_ptr<common::ValueVector> ownedRelIDVector;

    RelDeletionRecord()
        : WALRecord{WALRecordType::REL_DELETION_RECORD}, tableID{common::INVALID_TABLE_ID},
          srcNodeIDVector{nullptr}, dstNodeIDVector{nullptr}, relIDVector{nullptr} {}
    RelDeletionRecord(common::table_id_t tableID, common::ValueVector* srcNodeIDVector,
        common::ValueVector* dstNodeIDVector, common::ValueVector* relIDVector)
        : WALRecord{WALRecordType::REL_DELETION_RECORD}, tableID{tableID},
          srcNodeIDVector{srcNodeIDVector}, dstNodeIDVector{dstNodeIDVector},
          relIDVector{relIDVector} {}
    RelDeletionRecord(common::table_id_t tableID,
        std::unique_ptr<common::ValueVector> srcNodeIDVector,
        std::unique_ptr<common::ValueVector> dstNodeIDVector,
        std::unique_ptr<common::ValueVector> relIDVector)
        : WALRecord{WALRecordType::REL_DELETION_RECORD}, tableID{tableID}, srcNodeIDVector{nullptr},
          dstNodeIDVector{nullptr}, relIDVector{nullptr},
          ownedSrcNodeIDVector{std::move(srcNodeIDVector)},
          ownedDstNodeIDVector{std::move(dstNodeIDVector)},
          ownedRelIDVector{std::move(relIDVector)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<RelDeletionRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct RelDetachDeleteRecord final : WALRecord {
    common::table_id_t tableID;
    common::RelDataDirection direction;
    common::ValueVector* srcNodeIDVector;
    std::unique_ptr<common::ValueVector> ownedSrcNodeIDVector;

    RelDetachDeleteRecord()
        : WALRecord{WALRecordType::REL_DETACH_DELETE_RECORD}, tableID{common::INVALID_TABLE_ID},
          direction{common::RelDataDirection::FWD}, srcNodeIDVector{nullptr} {}
    RelDetachDeleteRecord(common::table_id_t tableID, common::RelDataDirection direction,
        common::ValueVector* srcNodeIDVector)
        : WALRecord{WALRecordType::REL_DETACH_DELETE_RECORD}, tableID{tableID},
          direction{direction}, srcNodeIDVector{srcNodeIDVector} {}
    RelDetachDeleteRecord(common::table_id_t tableID, common::RelDataDirection direction,
        std::unique_ptr<common::ValueVector> srcNodeIDVector)
        : WALRecord{WALRecordType::REL_DETACH_DELETE_RECORD}, tableID{tableID},
          direction{direction}, srcNodeIDVector{nullptr},
          ownedSrcNodeIDVector{std::move(srcNodeIDVector)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<RelDetachDeleteRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct RelUpdateRecord final : WALRecord {
    common::table_id_t tableID;
    common::column_id_t columnID;
    common::ValueVector* srcNodeIDVector;
    common::ValueVector* dstNodeIDVector;
    common::ValueVector* relIDVector;
    common::ValueVector* propertyVector;
    std::unique_ptr<common::ValueVector> ownedSrcNodeIDVector;
    std::unique_ptr<common::ValueVector> ownedDstNodeIDVector;
    std::unique_ptr<common::ValueVector> ownedRelIDVector;
    std::unique_ptr<common::ValueVector> ownedPropertyVector;

    RelUpdateRecord()
        : WALRecord{WALRecordType::REL_UPDATE_RECORD}, tableID{common::INVALID_TABLE_ID},
          columnID{common::INVALID_COLUMN_ID}, srcNodeIDVector{nullptr}, dstNodeIDVector{nullptr},
          relIDVector{nullptr}, propertyVector{nullptr} {}
    RelUpdateRecord(common::table_id_t tableID, common::column_id_t columnID,
        common::ValueVector* srcNodeIDVector, common::ValueVector* dstNodeIDVector,
        common::ValueVector* relIDVector, common::ValueVector* propertyVector)
        : WALRecord{WALRecordType::REL_UPDATE_RECORD}, tableID{tableID}, columnID{columnID},
          srcNodeIDVector{srcNodeIDVector}, dstNodeIDVector{dstNodeIDVector},
          relIDVector{relIDVector}, propertyVector{propertyVector} {}
    RelUpdateRecord(common::table_id_t tableID, common::column_id_t columnID,
        std::unique_ptr<common::ValueVector> srcNodeIDVector,
        std::unique_ptr<common::ValueVector> dstNodeIDVector,
        std::unique_ptr<common::ValueVector> relIDVector,
        std::unique_ptr<common::ValueVector> propertyVector)
        : WALRecord{WALRecordType::REL_UPDATE_RECORD}, tableID{tableID}, columnID{columnID},
          srcNodeIDVector{nullptr}, dstNodeIDVector{nullptr}, relIDVector{nullptr},
          propertyVector{nullptr}, ownedSrcNodeIDVector{std::move(srcNodeIDVector)},
          ownedDstNodeIDVector{std::move(dstNodeIDVector)},
          ownedRelIDVector{std::move(relIDVector)}, ownedPropertyVector{std::move(propertyVector)} {
    }

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<RelUpdateRecord> deserialize(common::Deserializer& deserializer,
        const main::ClientContext& clientContext);
};

struct LoadExtensionRecord final : WALRecord {
    std::string path;

    explicit LoadExtensionRecord(std::string path)
        : WALRecord{WALRecordType::LOAD_EXTENSION_RECORD}, path{std::move(path)} {}

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<LoadExtensionRecord> deserialize(common::Deserializer& deserializer);
};

} // namespace storage
} // namespace lbug
