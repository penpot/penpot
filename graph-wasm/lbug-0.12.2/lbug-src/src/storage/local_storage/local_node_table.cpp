#include "storage/local_storage/local_node_table.h"

#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "common/cast.h"
#include "common/exception/message.h"
#include "common/types/types.h"
#include "common/types/value/value.h"
#include "storage/index/hash_index.h"
#include "storage/storage_utils.h"
#include "storage/table/node_table.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

std::vector<LogicalType> LocalNodeTable::getNodeTableColumnTypes(
    const catalog::TableCatalogEntry& table) {
    std::vector<LogicalType> types;
    for (auto& property : table.getProperties()) {
        types.push_back(property.getType().copy());
    }
    return types;
}

LocalNodeTable::LocalNodeTable(const catalog::TableCatalogEntry* tableEntry, Table& table,
    MemoryManager& mm)
    : LocalTable{table}, overflowFileHandle(nullptr),
      nodeGroups{mm, getNodeTableColumnTypes(*tableEntry), false /*enableCompression*/} {
    initLocalHashIndex(mm);
    startOffset = table.getNumTotalRows(nullptr /* transaction */);
}

void LocalNodeTable::initLocalHashIndex(MemoryManager& mm) {
    auto& nodeTable = ku_dynamic_cast<const NodeTable&>(table);
    overflowFile = std::make_unique<InMemOverflowFile>(mm);
    overflowFileHandle = overflowFile->addHandle();
    hashIndex = std::make_unique<LocalHashIndex>(mm,
        nodeTable.getColumn(nodeTable.getPKColumnID()).getDataType().getPhysicalType(),
        overflowFileHandle);
}

bool LocalNodeTable::isVisible(const Transaction* transaction, offset_t offset) const {
    auto [nodeGroupIdx, offsetInGroup] =
        StorageUtils::getNodeGroupIdxAndOffsetInChunk(offset - startOffset);
    auto* nodeGroup = nodeGroups.getNodeGroup(nodeGroupIdx);
    if (nodeGroup->isDeleted(transaction, offsetInGroup)) {
        return false;
    }
    return nodeGroup->isInserted(transaction, offsetInGroup);
}

offset_t LocalNodeTable::validateUniquenessConstraint(const Transaction* transaction,
    const ValueVector& pkVector) const {
    KU_ASSERT(pkVector.state->getSelVector().getSelSize() == 1);
    return hashIndex->lookup(pkVector,
        [&](offset_t offset_) { return isVisible(transaction, offset_); });
}

bool LocalNodeTable::insert(Transaction* transaction, TableInsertState& insertState) {
    auto& nodeInsertState = insertState.constCast<NodeTableInsertState>();
    const auto nodeOffset = startOffset + nodeGroups.getNumTotalRows();
    KU_ASSERT(nodeInsertState.pkVector.state->getSelVector().getSelSize() == 1);
    if (!hashIndex->insert(nodeInsertState.pkVector, nodeOffset,
            [&](offset_t offset) { return isVisible(transaction, offset); })) {
        const auto val =
            nodeInsertState.pkVector.getAsValue(nodeInsertState.pkVector.state->getSelVector()[0]);
        throw RuntimeException(ExceptionMessage::duplicatePKException(val->toString()));
    }
    const auto nodeIDPos =
        nodeInsertState.nodeIDVector.state->getSelVector().getSelectedPositions()[0];
    nodeInsertState.nodeIDVector.setValue(nodeIDPos, internalID_t{nodeOffset, table.getTableID()});
    nodeGroups.append(&DUMMY_TRANSACTION, insertState.propertyVectors);
    return true;
}

bool LocalNodeTable::update(Transaction* transaction, TableUpdateState& updateState) {
    KU_ASSERT(transaction->isDummy());
    const auto& nodeUpdateState = updateState.cast<NodeTableUpdateState>();
    KU_ASSERT(nodeUpdateState.nodeIDVector.state->getSelVector().getSelSize() == 1);
    const auto pos = nodeUpdateState.nodeIDVector.state->getSelVector()[0];
    const auto offset = nodeUpdateState.nodeIDVector.readNodeOffset(pos);
    KU_ASSERT(nodeUpdateState.columnID != table.cast<NodeTable>().getPKColumnID());
    KU_ASSERT(offset >= startOffset);
    const auto [nodeGroupIdx, rowIdxInGroup] =
        StorageUtils::getQuotientRemainder(offset - startOffset, StorageConfig::NODE_GROUP_SIZE);
    const auto nodeGroup = nodeGroups.getNodeGroup(nodeGroupIdx);
    nodeGroup->update(transaction, rowIdxInGroup, nodeUpdateState.columnID,
        nodeUpdateState.propertyVector);
    return true;
}

bool LocalNodeTable::delete_(Transaction* transaction, TableDeleteState& deleteState) {
    KU_ASSERT(transaction->isDummy());
    const auto& nodeDeleteState = deleteState.cast<NodeTableDeleteState>();
    KU_ASSERT(nodeDeleteState.nodeIDVector.state->getSelVector().getSelSize() == 1);
    const auto pos = nodeDeleteState.nodeIDVector.state->getSelVector()[0];
    const auto offset = nodeDeleteState.nodeIDVector.readNodeOffset(pos);
    KU_ASSERT(offset >= startOffset);
    hashIndex->delete_(nodeDeleteState.pkVector);
    const auto [nodeGroupIdx, rowIdxInGroup] =
        StorageUtils::getQuotientRemainder(offset - startOffset, StorageConfig::NODE_GROUP_SIZE);
    const auto nodeGroup = nodeGroups.getNodeGroup(nodeGroupIdx);
    return nodeGroup->delete_(transaction, rowIdxInGroup);
}

bool LocalNodeTable::addColumn(TableAddColumnState& addColumnState) {
    nodeGroups.addColumn(addColumnState);
    return true;
}

void LocalNodeTable::clear(MemoryManager& mm) {
    auto& nodeTable = ku_dynamic_cast<const NodeTable&>(table);
    hashIndex = std::make_unique<LocalHashIndex>(mm,
        nodeTable.getColumn(nodeTable.getPKColumnID()).getDataType().getPhysicalType(),
        overflowFileHandle);
    nodeGroups.clear();
}

bool LocalNodeTable::lookupPK(const Transaction* transaction, const ValueVector* keyVector,
    sel_t pos, offset_t& result) const {
    result = hashIndex->lookup(*keyVector, pos,
        [&](offset_t offset) { return isVisible(transaction, offset); });
    return result != INVALID_OFFSET;
}

} // namespace storage
} // namespace lbug
