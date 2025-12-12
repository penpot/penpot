#pragma once

#include "common/copy_constructors.h"
#include "storage/local_storage/local_hash_index.h"
#include "storage/local_storage/local_table.h"
#include "storage/table/node_group_collection.h"

namespace lbug {
namespace storage {

struct TableScanState;
class MemoryManager;

class LocalNodeTable final : public LocalTable {
public:
    LocalNodeTable(const catalog::TableCatalogEntry* tableEntry, Table& table, MemoryManager& mm);
    DELETE_COPY_AND_MOVE(LocalNodeTable);

    bool insert(transaction::Transaction* transaction, TableInsertState& insertState) override;
    bool update(transaction::Transaction* transaction, TableUpdateState& updateState) override;
    bool delete_(transaction::Transaction* transaction, TableDeleteState& deleteState) override;
    bool addColumn(TableAddColumnState& addColumnState) override;

    common::offset_t validateUniquenessConstraint(const transaction::Transaction* transaction,
        const common::ValueVector& pkVector) const;

    common::TableType getTableType() const override { return common::TableType::NODE; }

    void clear(MemoryManager& mm) override;

    common::row_idx_t getNumTotalRows() override { return nodeGroups.getNumTotalRows(); }
    common::node_group_idx_t getNumNodeGroups() const { return nodeGroups.getNumNodeGroups(); }

    NodeGroup* getNodeGroup(common::node_group_idx_t nodeGroupIdx) const {
        return nodeGroups.getNodeGroup(nodeGroupIdx);
    }
    NodeGroupCollection& getNodeGroups() { return nodeGroups; }

    bool lookupPK(const transaction::Transaction* transaction, const common::ValueVector* keyVector,
        common::sel_t pos, common::offset_t& result) const;

    TableStats getStats() const { return nodeGroups.getStats(); }
    common::offset_t getStartOffset() const { return startOffset; }

    static std::vector<common::LogicalType> getNodeTableColumnTypes(
        const catalog::TableCatalogEntry& table);

private:
    void initLocalHashIndex(MemoryManager& mm);
    bool isVisible(const transaction::Transaction* transaction, common::offset_t offset) const;

private:
    // This is equivalent to the num of committed nodes in the table.
    common::offset_t startOffset;
    PageCursor overflowCursor;
    std::unique_ptr<OverflowFile> overflowFile;
    OverflowFileHandle* overflowFileHandle;
    std::unique_ptr<LocalHashIndex> hashIndex;
    NodeGroupCollection nodeGroups;
};

} // namespace storage
} // namespace lbug
