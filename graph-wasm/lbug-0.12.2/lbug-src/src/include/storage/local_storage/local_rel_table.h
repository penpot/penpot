#pragma once

#include <map>

#include "common/enums/rel_direction.h"
#include "storage/local_storage/local_table.h"
#include "storage/table/csr_node_group.h"

namespace lbug {
namespace storage {
class MemoryManager;

static constexpr common::column_id_t LOCAL_BOUND_NODE_ID_COLUMN_ID = 0;
static constexpr common::column_id_t LOCAL_NBR_NODE_ID_COLUMN_ID = 1;
static constexpr common::column_id_t LOCAL_REL_ID_COLUMN_ID = 2;

class RelTable;
struct TableScanState;
struct RelTableUpdateState;

struct DirectedCSRIndex {
    using index_t = std::map<common::offset_t, row_idx_vec_t>;

    explicit DirectedCSRIndex(common::RelDataDirection direction) : direction(direction) {}

    bool isEmpty() const { return index.empty(); }
    void clear() { index.clear(); }

    common::RelDataDirection direction;
    index_t index;
};

class LocalRelTable final : public LocalTable {
public:
    LocalRelTable(const catalog::TableCatalogEntry* tableEntry, const Table& table,
        MemoryManager& mm);
    DELETE_COPY_AND_MOVE(LocalRelTable);

    bool insert(transaction::Transaction* transaction, TableInsertState& state) override;
    bool update(transaction::Transaction* transaction, TableUpdateState& state) override;
    bool delete_(transaction::Transaction* transaction, TableDeleteState& state) override;
    bool addColumn(TableAddColumnState& addColumnState) override;

    bool checkIfNodeHasRels(common::ValueVector* srcNodeIDVector,
        common::RelDataDirection direction) const;

    common::TableType getTableType() const override { return common::TableType::REL; }

    static void initializeScan(TableScanState& state);
    bool scan(const transaction::Transaction* transaction, TableScanState& state) const;

    void clear(MemoryManager&) override {
        localNodeGroup.reset();
        for (auto& index : directedIndices) {
            index.clear();
        }
    }
    bool isEmpty() const {
        KU_ASSERT(directedIndices.size() >= 1);
        RUNTIME_CHECK(for (const auto& index
                           : directedIndices) {
            KU_ASSERT(index.index.empty() == directedIndices[0].index.empty());
        });
        return directedIndices[0].isEmpty();
    }

    common::column_id_t getNumColumns() const { return localNodeGroup->getDataTypes().size(); }
    common::row_idx_t getNumTotalRows() override { return localNodeGroup->getNumRows(); }

    DirectedCSRIndex::index_t& getCSRIndex(common::RelDataDirection direction) {
        const auto directionIdx = common::RelDirectionUtils::relDirectionToKeyIdx(direction);
        KU_ASSERT(directionIdx < directedIndices.size());
        return directedIndices[directionIdx].index;
    }
    NodeGroup& getLocalNodeGroup() const { return *localNodeGroup; }

    static std::vector<common::column_id_t> rewriteLocalColumnIDs(
        common::RelDataDirection direction, const std::vector<common::column_id_t>& columnIDs);
    static common::column_id_t rewriteLocalColumnID(common::RelDataDirection direction,
        common::column_id_t columnID);

private:
    common::row_idx_t findMatchingRow(const transaction::Transaction* transaction,
        const std::vector<row_idx_vec_t*>& rowIndicesToCheck, common::offset_t relOffset) const;

private:
    // We don't duplicate local rel tuples. Tuples are stored same as node tuples.
    // Chunks stored in local rel table are organized as follows:
    // [srcNodeID, dstNodeID, relID, property1, property2, ...]
    // All local rel tuples are stored in a single node group, and they are indexed by src/dst
    // NodeID.
    std::vector<DirectedCSRIndex> directedIndices;
    std::unique_ptr<NodeGroup> localNodeGroup;
};

} // namespace storage
} // namespace lbug
