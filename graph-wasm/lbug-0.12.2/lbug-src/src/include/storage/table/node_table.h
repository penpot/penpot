#pragma once

#include "common/types/types.h"
#include "storage/index/hash_index.h"
#include "storage/table/node_group_collection.h"
#include "storage/table/table.h"

namespace lbug {
namespace evaluator {
class ExpressionEvaluator;
} // namespace evaluator

namespace catalog {
class NodeTableCatalogEntry;
} // namespace catalog

namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {

struct LBUG_API NodeTableScanState : TableScanState {
    NodeTableScanState(common::ValueVector* nodeIDVector,
        std::vector<common::ValueVector*> outputVectors,
        std::shared_ptr<common::DataChunkState> outChunkState)
        : TableScanState{nodeIDVector, std::move(outputVectors), std::move(outChunkState)} {
        nodeGroupScanState = std::make_unique<NodeGroupScanState>(this->columnIDs.size());
    }

    void setToTable(const transaction::Transaction* transaction, Table* table_,
        std::vector<common::column_id_t> columnIDs_,
        std::vector<ColumnPredicateSet> columnPredicateSets_ = {},
        common::RelDataDirection direction = common::RelDataDirection::INVALID) override;

    bool scanNext(transaction::Transaction* transaction) override;

    NodeGroupScanResult scanNext(transaction::Transaction* transaction,
        common::offset_t startOffset, common::offset_t numNodes);
};

// There is a vtable bug related to the Apple clang v15.0.0+. Adding the `FINAL` specifier to
// derived class causes casting failures in Apple platform.
struct LBUG_API NodeTableInsertState : TableInsertState {
    common::ValueVector& nodeIDVector;
    const common::ValueVector& pkVector;
    std::vector<std::unique_ptr<Index::InsertState>> indexInsertStates;

    NodeTableInsertState(common::ValueVector& nodeIDVector, const common::ValueVector& pkVector,
        std::vector<common::ValueVector*> propertyVectors)
        : TableInsertState{std::move(propertyVectors)}, nodeIDVector{nodeIDVector},
          pkVector{pkVector} {}

    NodeTableInsertState(const NodeTableInsertState&) = delete;
};

struct LBUG_API NodeTableUpdateState : TableUpdateState {
    common::ValueVector& nodeIDVector;
    std::vector<std::unique_ptr<Index::UpdateState>> indexUpdateState;

    NodeTableUpdateState(common::column_id_t columnID, common::ValueVector& nodeIDVector,
        common::ValueVector& propertyVector)
        : TableUpdateState{columnID, propertyVector}, nodeIDVector{nodeIDVector} {}

    NodeTableUpdateState(const NodeTableUpdateState&) = delete;

    bool needToUpdateIndex(common::idx_t idx) const {
        return idx < indexUpdateState.size() && indexUpdateState[idx] != nullptr;
    }
};

struct LBUG_API NodeTableDeleteState : TableDeleteState {
    common::ValueVector& nodeIDVector;
    common::ValueVector& pkVector;

    explicit NodeTableDeleteState(common::ValueVector& nodeIDVector, common::ValueVector& pkVector)
        : nodeIDVector{nodeIDVector}, pkVector{pkVector} {}
};

class NodeTable;
struct IndexScanHelper {
    explicit IndexScanHelper(NodeTable* table, Index* index) : table{table}, index(index) {}
    virtual ~IndexScanHelper() = default;

    virtual std::unique_ptr<NodeTableScanState> initScanState(
        const transaction::Transaction* transaction, common::DataChunk& dataChunk);
    virtual bool processScanOutput(main::ClientContext* context, NodeGroupScanResult scanResult,
        const std::vector<common::ValueVector*>& scannedVectors) = 0;

    NodeTable* table;
    Index* index;
};

class NodeTableVersionRecordHandler final : public VersionRecordHandler {
public:
    explicit NodeTableVersionRecordHandler(NodeTable* table);

    void applyFuncToChunkedGroups(version_record_handler_op_t func,
        common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
        common::row_idx_t numRows, common::transaction_t commitTS) const override;
    void rollbackInsert(main::ClientContext* context, common::node_group_idx_t nodeGroupIdx,
        common::row_idx_t startRow, common::row_idx_t numRows) const override;

private:
    NodeTable* table;
};

class StorageManager;

class LBUG_API NodeTable final : public Table {
public:
    NodeTable(const StorageManager* storageManager,
        const catalog::NodeTableCatalogEntry* nodeTableEntry, MemoryManager* mm);

    common::row_idx_t getNumTotalRows(const transaction::Transaction* transaction) override;

    void initScanState(transaction::Transaction* transaction, TableScanState& scanState,
        bool resetCachedBoundNodeIDs = true) const override;
    void initScanState(transaction::Transaction* transaction, TableScanState& scanState,
        common::table_id_t tableID, common::offset_t startOffset) const;

    bool scanInternal(transaction::Transaction* transaction, TableScanState& scanState) override;
    template<bool lock = true>
    bool lookup(const transaction::Transaction* transaction, const TableScanState& scanState) const;
    // TODO(Guodong): This should be merged together with `lookup`.
    template<bool lock = true>
    bool lookupMultiple(transaction::Transaction* transaction, TableScanState& scanState) const;

    // Return the max node offset during insertions.
    common::offset_t validateUniquenessConstraint(const transaction::Transaction* transaction,
        const std::vector<common::ValueVector*>& propertyVectors) const;

    void initInsertState(main::ClientContext* context, TableInsertState& insertState) override;
    void insert(transaction::Transaction* transaction, TableInsertState& insertState) override;
    void initUpdateState(main::ClientContext* context, TableUpdateState& updateState) const;
    void update(transaction::Transaction* transaction, TableUpdateState& updateState) override;
    bool delete_(transaction::Transaction* transaction, TableDeleteState& deleteState) override;

    void addColumn(transaction::Transaction* transaction, TableAddColumnState& addColumnState,
        PageAllocator& pageAllocator) override;
    bool isVisible(const transaction::Transaction* transaction, common::offset_t offset) const;
    bool isVisibleNoLock(const transaction::Transaction* transaction,
        common::offset_t offset) const;

    bool lookupPK(const transaction::Transaction* transaction, common::ValueVector* keyVector,
        uint64_t vectorPos, common::offset_t& result) const;

    void addIndex(std::unique_ptr<Index> index);
    void dropIndex(const std::string& name);

    common::column_id_t getPKColumnID() const { return pkColumnID; }
    PrimaryKeyIndex* getPKIndex() const {
        const auto index = getIndex(PrimaryKeyIndex::DEFAULT_NAME);
        KU_ASSERT(index.has_value());
        return &index.value()->cast<PrimaryKeyIndex>();
    }
    std::optional<std::reference_wrapper<IndexHolder>> getIndexHolder(const std::string& name);
    std::optional<Index*> getIndex(const std::string& name) const;
    std::vector<IndexHolder>& getIndexes() { return indexes; }

    common::column_id_t getNumColumns() const { return columns.size(); }
    Column& getColumn(common::column_id_t columnID) {
        KU_ASSERT(columnID < columns.size());
        return *columns[columnID];
    }
    const Column& getColumn(common::column_id_t columnID) const {
        KU_ASSERT(columnID < columns.size());
        return *columns[columnID];
    }

    std::pair<common::offset_t, common::offset_t> appendToLastNodeGroup(
        transaction::Transaction* transaction, const std::vector<common::column_id_t>& columnIDs,
        InMemChunkedNodeGroup& chunkedGroup, PageAllocator& pageAllocator);

    void commit(main::ClientContext* context, catalog::TableCatalogEntry* tableEntry,
        LocalTable* localTable) override;
    bool checkpoint(main::ClientContext* context, catalog::TableCatalogEntry* tableEntry,
        PageAllocator& pageAllocator) override;
    void rollbackCheckpoint() override;
    void reclaimStorage(PageAllocator& pageAllocator) const override;

    void rollbackPKIndexInsert(main::ClientContext* context, common::row_idx_t startRow,
        common::row_idx_t numRows_, common::node_group_idx_t nodeGroupIdx_);
    void rollbackGroupCollectionInsert(common::row_idx_t numRows_);

    common::node_group_idx_t getNumCommittedNodeGroups() const {
        return nodeGroups->getNumNodeGroups();
    }

    common::node_group_idx_t getNumNodeGroups() const { return nodeGroups->getNumNodeGroups(); }
    common::offset_t getNumTuplesInNodeGroup(common::node_group_idx_t nodeGroupIdx) const {
        return nodeGroups->getNodeGroup(nodeGroupIdx)->getNumRows();
    }
    NodeGroup* getNodeGroup(common::node_group_idx_t nodeGroupIdx) const {
        return nodeGroups->getNodeGroup(nodeGroupIdx);
    }
    NodeGroup* getNodeGroupNoLock(common::node_group_idx_t nodeGroupIdx) const {
        return nodeGroups->getNodeGroupNoLock(nodeGroupIdx);
    }

    TableStats getStats(const transaction::Transaction* transaction) const;
    // NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
    void mergeStats(const std::vector<common::column_id_t>& columnIDs, const TableStats& stats) {
        nodeGroups->mergeStats(columnIDs, stats);
    }

    void serialize(common::Serializer& serializer) const override;
    void deserialize(main::ClientContext* context, StorageManager* storageManager,
        common::Deserializer& deSer) override;

private:
    void validatePkNotExists(const transaction::Transaction* transaction,
        common::ValueVector* pkVector) const;

    visible_func getVisibleFunc(const transaction::Transaction* transaction) const;
    common::DataChunk constructDataChunkForColumns(
        const std::vector<common::column_id_t>& columnIDs) const;
    void scanIndexColumns(main::ClientContext* context, IndexScanHelper& scanHelper,
        const NodeGroupCollection& nodeGroups_) const;

private:
    std::vector<std::unique_ptr<Column>> columns;
    std::unique_ptr<NodeGroupCollection> nodeGroups;
    common::column_id_t pkColumnID;
    std::vector<IndexHolder> indexes;
    NodeTableVersionRecordHandler versionRecordHandler;
};

} // namespace storage
} // namespace lbug
