#pragma once

#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/enums/rel_direction.h"
#include "common/mask.h"
#include "storage/predicate/column_predicate.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/node_group.h"

namespace lbug {
namespace evaluator {
class ExpressionEvaluator;
} // namespace evaluator
namespace storage {
class MemoryManager;
class Table;

enum class TableScanSource : uint8_t { COMMITTED = 0, UNCOMMITTED = 1, NONE = UINT8_MAX };

struct LBUG_API TableScanState {
    Table* table;
    std::unique_ptr<common::ValueVector> rowIdxVector;
    // Node/Rel ID vector. We assume all output vectors are within the same DataChunk as this one.
    common::ValueVector* nodeIDVector;
    std::vector<common::ValueVector*> outputVectors;
    std::shared_ptr<common::DataChunkState> outState;
    std::vector<common::column_id_t> columnIDs;
    common::SemiMask* semiMask;

    // Only used when scan from persistent data.
    std::vector<const Column*> columns;

    TableScanSource source;
    common::node_group_idx_t nodeGroupIdx;
    NodeGroup* nodeGroup = nullptr;
    std::unique_ptr<NodeGroupScanState> nodeGroupScanState;

    std::vector<ColumnPredicateSet> columnPredicateSets;

    TableScanState(common::ValueVector* nodeIDVector,
        std::vector<common::ValueVector*> outputVectors,
        std::shared_ptr<common::DataChunkState> outChunkState)
        : table{nullptr}, nodeIDVector(nodeIDVector), outputVectors{std::move(outputVectors)},
          outState{std::move(outChunkState)}, semiMask{nullptr}, source{TableScanSource::NONE},
          nodeGroupIdx{common::INVALID_NODE_GROUP_IDX} {
        rowIdxVector = std::make_unique<common::ValueVector>(common::LogicalType::INT64());
        rowIdxVector->state = outState;
    }

    TableScanState(std::vector<common::column_id_t> columnIDs, std::vector<const Column*> columns)
        : table{nullptr}, nodeIDVector(nullptr), outState{nullptr}, columnIDs{std::move(columnIDs)},
          semiMask{nullptr}, columns{std::move(columns)}, source{TableScanSource::NONE},
          nodeGroupIdx{common::INVALID_NODE_GROUP_IDX} {}

    virtual ~TableScanState();
    DELETE_COPY_DEFAULT_MOVE(TableScanState);

    virtual void setToTable(const transaction::Transaction* transaction, Table* table_,
        std::vector<common::column_id_t> columnIDs_,
        std::vector<ColumnPredicateSet> columnPredicateSets_,
        common::RelDataDirection direction = common::RelDataDirection::INVALID);

    // Note that `resetCachedBoundNodeSelVec` is only applicable to RelTable for now.
    virtual void initState(transaction::Transaction* transaction, NodeGroup* nodeGroup,
        bool /*resetCachedBoundNodeSelVev*/ = true) {
        KU_ASSERT(nodeGroup);
        this->nodeGroup = nodeGroup;
        this->nodeGroup->initializeScanState(transaction, *this);
    }

    virtual bool scanNext(transaction::Transaction*) { KU_UNREACHABLE; }

    void resetOutVectors();

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

struct LBUG_API TableInsertState {
    std::vector<common::ValueVector*> propertyVectors;
    // TODO(Guodong): Remove this when we have a better way to skip WAL logging for FTS.
    bool logToWAL;

    explicit TableInsertState(std::vector<common::ValueVector*> propertyVectors);
    virtual ~TableInsertState();

    template<typename T>
    const T& constCast() const {
        return common::ku_dynamic_cast<const T&>(*this);
    }
    template<typename T>
    T& cast() {
        return common::ku_dynamic_cast<T&>(*this);
    }
};

struct LBUG_API TableUpdateState {
    common::column_id_t columnID;
    common::ValueVector& propertyVector;
    // TODO(Guodong): Remove this when we have a better way to skip WAL logging for FTS.
    bool logToWAL;

    TableUpdateState(common::column_id_t columnID, common::ValueVector& propertyVector);
    virtual ~TableUpdateState();

    template<typename T>
    const T& constCast() const {
        return common::ku_dynamic_cast<const T&>(*this);
    }
    template<typename T>
    T& cast() {
        return common::ku_dynamic_cast<T&>(*this);
    }
};

struct LBUG_API TableDeleteState {
    bool logToWAL;

    TableDeleteState();

    virtual ~TableDeleteState();

    template<typename T>
    const T& constCast() const {
        return common::ku_dynamic_cast<const T&>(*this);
    }
    template<typename T>
    T& cast() {
        return common::ku_dynamic_cast<T&>(*this);
    }
};

struct TableAddColumnState final {
    const binder::PropertyDefinition& propertyDefinition;
    evaluator::ExpressionEvaluator& defaultEvaluator;

    TableAddColumnState(const binder::PropertyDefinition& propertyDefinition,
        evaluator::ExpressionEvaluator& defaultEvaluator)
        : propertyDefinition{propertyDefinition}, defaultEvaluator{defaultEvaluator} {}
    ~TableAddColumnState() = default;
};

class LocalTable;
class StorageManager;
class LBUG_API Table {
public:
    Table(const catalog::TableCatalogEntry* tableEntry, const StorageManager* storageManager,
        MemoryManager* memoryManager);
    virtual ~Table();

    common::TableType getTableType() const { return tableType; }
    common::table_id_t getTableID() const { return tableID; }
    std::string getTableName() const { return tableName; }

    // Note that `resetCachedBoundNodeIDs` is only applicable to RelTable for now.
    virtual void initScanState(transaction::Transaction* transaction, TableScanState& readState,
        bool resetCachedBoundNodeSelVec = true) const = 0;
    bool scan(transaction::Transaction* transaction, TableScanState& scanState);

    virtual void initInsertState(main::ClientContext* context, TableInsertState& insertState) = 0;
    virtual void insert(transaction::Transaction* transaction, TableInsertState& insertState) = 0;
    virtual void update(transaction::Transaction* transaction, TableUpdateState& updateState) = 0;
    virtual bool delete_(transaction::Transaction* transaction, TableDeleteState& deleteState) = 0;

    virtual void addColumn(transaction::Transaction* transaction,
        TableAddColumnState& addColumnState, PageAllocator& pageAllocator) = 0;
    void dropColumn() { setHasChanges(); }

    virtual void commit(main::ClientContext* context, catalog::TableCatalogEntry* tableEntry,
        LocalTable* localTable) = 0;
    virtual bool checkpoint(main::ClientContext* context, catalog::TableCatalogEntry* tableEntry,
        PageAllocator& pageAllocator) = 0;
    virtual void rollbackCheckpoint() = 0;
    virtual void reclaimStorage(PageAllocator& pageAllocator) const = 0;

    virtual common::row_idx_t getNumTotalRows(const transaction::Transaction* transaction) = 0;

    void setHasChanges() { hasChanges = true; }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

    static common::DataChunk constructDataChunk(MemoryManager* mm,
        std::vector<common::LogicalType> types);

    virtual void serialize(common::Serializer& serializer) const = 0;
    virtual void deserialize(main::ClientContext* context, StorageManager* storageManager,
        common::Deserializer& deSer) = 0;

protected:
    virtual bool scanInternal(transaction::Transaction* transaction, TableScanState& scanState) = 0;

protected:
    common::TableType tableType;
    common::table_id_t tableID;
    std::string tableName;
    bool enableCompression;
    MemoryManager* memoryManager;
    ShadowFile* shadowFile;
    std::atomic<bool> hasChanges;
};

} // namespace storage
} // namespace lbug
