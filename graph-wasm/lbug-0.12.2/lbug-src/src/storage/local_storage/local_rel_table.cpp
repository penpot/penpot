#include "storage/local_storage/local_rel_table.h"

#include <algorithm>
#include <numeric>

#include "common/enums/rel_direction.h"
#include "storage/table/rel_table.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

static std::vector<LogicalType> getTypesForLocalRelTable(const catalog::TableCatalogEntry& table) {
    std::vector<LogicalType> types;
    types.reserve(table.getNumProperties() + 2);
    // Pre-append src and dst node ID columns.
    types.push_back(LogicalType::INTERNAL_ID());
    types.push_back(LogicalType::INTERNAL_ID());
    for (auto& property : table.getProperties()) {
        types.push_back(property.getType().copy());
    }
    return types;
}

LocalRelTable::LocalRelTable(const catalog::TableCatalogEntry* tableEntry, const Table& table,
    MemoryManager& mm)
    : LocalTable{table} {
    localNodeGroup = std::make_unique<NodeGroup>(mm, 0, false,
        getTypesForLocalRelTable(*tableEntry), INVALID_ROW_IDX);
    const auto& relTable = table.cast<RelTable>();
    for (auto relDirection : relTable.getStorageDirections()) {
        directedIndices.emplace_back(relDirection);
    }
}

bool LocalRelTable::insert(Transaction*, TableInsertState& state) {
    const auto& insertState = state.cast<RelTableInsertState>();

    std::vector<row_idx_vec_t*> rowIndicesToInsertTo;
    for (auto& directedIndex : directedIndices) {
        const auto& nodeIDVector = insertState.getBoundNodeIDVector(directedIndex.direction);
        KU_ASSERT(nodeIDVector.state->getSelVector().getSelSize() == 1);
        auto nodePos = nodeIDVector.state->getSelVector()[0];
        if (nodeIDVector.isNull(nodePos)) {
            return false;
        }
        auto nodeOffset = nodeIDVector.readNodeOffset(nodePos);
        rowIndicesToInsertTo.push_back(&directedIndex.index[nodeOffset]);
    }

    const auto numRowsInLocalTable = localNodeGroup->getNumRows();
    const auto relOffset = StorageConstants::MAX_NUM_ROWS_IN_TABLE + numRowsInLocalTable;
    const auto relIDVector = insertState.propertyVectors[0];
    KU_ASSERT(relIDVector->dataType.getPhysicalType() == PhysicalTypeID::INTERNAL_ID);
    const auto relIDPos = relIDVector->state->getSelVector()[0];
    relIDVector->setValue<internalID_t>(relIDPos, internalID_t{relOffset, table.getTableID()});
    relIDVector->setNull(relIDPos, false);
    std::vector<ValueVector*> insertVectors;
    insertVectors.push_back(&insertState.srcNodeIDVector);
    insertVectors.push_back(&insertState.dstNodeIDVector);
    for (auto i = 0u; i < insertState.propertyVectors.size(); i++) {
        insertVectors.push_back(insertState.propertyVectors[i]);
    }
    const auto numRowsToAppend = insertState.srcNodeIDVector.state->getSelVector().getSelSize();
    localNodeGroup->append(&DUMMY_TRANSACTION, insertVectors, 0, numRowsToAppend);

    for (auto* rowIndexToInsertTo : rowIndicesToInsertTo) {
        rowIndexToInsertTo->push_back(numRowsInLocalTable);
    }

    return true;
}

bool LocalRelTable::update(Transaction* transaction, TableUpdateState& state) {
    KU_ASSERT(transaction->isDummy());
    const auto& updateState = state.cast<RelTableUpdateState>();

    std::vector<row_idx_vec_t*> rowIndicesToUpdate;
    for (auto& directedIndex : directedIndices) {
        const auto& nodeIDVector = updateState.getBoundNodeIDVector(directedIndex.direction);
        KU_ASSERT(nodeIDVector.state->getSelVector().getSelSize() == 1);
        auto nodePos = nodeIDVector.state->getSelVector()[0];
        if (nodeIDVector.isNull(nodePos)) {
            return false;
        }
        auto nodeOffset = nodeIDVector.readNodeOffset(nodePos);
        rowIndicesToUpdate.push_back(&directedIndex.index[nodeOffset]);
    }

    const auto relIDPos = updateState.relIDVector.state->getSelVector()[0];
    if (updateState.relIDVector.isNull(relIDPos)) {
        return false;
    }
    const auto relOffset = updateState.relIDVector.readNodeOffset(relIDPos);
    const auto matchedRow = findMatchingRow(transaction, rowIndicesToUpdate, relOffset);
    if (matchedRow == INVALID_ROW_IDX) {
        return false;
    }
    KU_ASSERT(updateState.columnID != NBR_ID_COLUMN_ID);
    localNodeGroup->update(transaction, matchedRow,
        rewriteLocalColumnID(RelDataDirection::FWD /* This is a dummy direction */,
            updateState.columnID),
        updateState.propertyVector);
    return true;
}

bool LocalRelTable::delete_(Transaction* transaction, TableDeleteState& state) {
    const auto& deleteState = state.cast<RelTableDeleteState>();

    std::vector<row_idx_vec_t*> rowIndicesToDeleteFrom;
    auto& directedIndex =
        directedIndices[RelDirectionUtils::relDirectionToKeyIdx(deleteState.detachDeleteDirection)];
    auto& reverseDirectedIndex = directedIndices[RelDirectionUtils::relDirectionToKeyIdx(
        RelDirectionUtils::getOppositeDirection(deleteState.detachDeleteDirection))];
    std::vector<std::pair<DirectedCSRIndex&, ValueVector&>> directedIndicesAndNodeIDVectors;
    auto directedIndexPos =
        RelDirectionUtils::relDirectionToKeyIdx(deleteState.detachDeleteDirection);
    if (directedIndexPos < directedIndices.size()) {
        directedIndicesAndNodeIDVectors.emplace_back(directedIndex, deleteState.srcNodeIDVector);
    }
    auto reverseDirectedIndexPos = RelDirectionUtils::relDirectionToKeyIdx(
        RelDirectionUtils::getOppositeDirection(deleteState.detachDeleteDirection));
    if (reverseDirectedIndexPos < directedIndices.size()) {
        directedIndicesAndNodeIDVectors.emplace_back(reverseDirectedIndex,
            deleteState.dstNodeIDVector);
    }
    for (auto& [csrIndex, nodeIDVector] : directedIndicesAndNodeIDVectors) {
        KU_ASSERT(nodeIDVector.state->getSelVector().getSelSize() == 1);
        auto nodePos = nodeIDVector.state->getSelVector()[0];
        if (nodeIDVector.isNull(nodePos)) {
            return false;
        }
        auto nodeOffset = nodeIDVector.readNodeOffset(nodePos);
        KU_ASSERT(csrIndex.index.contains(nodeOffset));
        rowIndicesToDeleteFrom.push_back(&csrIndex.index[nodeOffset]);
    }

    const auto relIDPos = deleteState.relIDVector.state->getSelVector()[0];
    if (deleteState.relIDVector.isNull(relIDPos)) {
        return false;
    }
    const auto relOffset = deleteState.relIDVector.readNodeOffset(relIDPos);
    const auto matchedRow = findMatchingRow(transaction, rowIndicesToDeleteFrom, relOffset);
    if (matchedRow == INVALID_ROW_IDX) {
        return false;
    }

    for (auto* rowIndexToDeleteFrom : rowIndicesToDeleteFrom) {
        std::erase(*rowIndexToDeleteFrom, matchedRow);
    }
    return true;
}

bool LocalRelTable::addColumn(TableAddColumnState& addColumnState) {
    localNodeGroup->addColumn(addColumnState, nullptr /* FileHandle */,
        nullptr /* newColumnStats */);
    return true;
}

bool LocalRelTable::checkIfNodeHasRels(ValueVector* srcNodeIDVector,
    RelDataDirection direction) const {
    KU_ASSERT(srcNodeIDVector->state->isFlat());
    const auto nodeIDPos = srcNodeIDVector->state->getSelVector()[0];
    const auto nodeOffset = srcNodeIDVector->getValue<nodeID_t>(nodeIDPos).offset;
    const auto& directedIndex =
        directedIndices[RelDirectionUtils::relDirectionToKeyIdx(direction)].index;
    return (directedIndex.contains(nodeOffset) && !directedIndex.at(nodeOffset).empty());
}

void LocalRelTable::initializeScan(TableScanState& state) {
    auto& relScanState = state.cast<RelTableScanState>();
    KU_ASSERT(relScanState.source == TableScanSource::UNCOMMITTED);
    KU_ASSERT(relScanState.localTableScanState);
    auto& localScanState = *relScanState.localTableScanState;
    localScanState.rowIndices.clear();
    localScanState.nextRowToScan = 0;
}

std::vector<column_id_t> LocalRelTable::rewriteLocalColumnIDs(RelDataDirection direction,
    const std::vector<column_id_t>& columnIDs) {
    std::vector<column_id_t> localColumnIDs;
    localColumnIDs.reserve(columnIDs.size());
    for (auto i = 0u; i < columnIDs.size(); i++) {
        const auto columnID = columnIDs[i];
        localColumnIDs.push_back(rewriteLocalColumnID(direction, columnID));
    }
    return localColumnIDs;
}

column_id_t LocalRelTable::rewriteLocalColumnID(RelDataDirection direction, column_id_t columnID) {
    return columnID == NBR_ID_COLUMN_ID ? direction == RelDataDirection::FWD ?
                                          LOCAL_NBR_NODE_ID_COLUMN_ID :
                                          LOCAL_BOUND_NODE_ID_COLUMN_ID :
                                          columnID + 1;
}

bool LocalRelTable::scan(const Transaction* transaction, TableScanState& state) const {
    auto& relScanState = state.cast<RelTableScanState>();
    KU_ASSERT(relScanState.localTableScanState);
    auto& localScanState = *relScanState.localTableScanState;
    while (true) {
        if (relScanState.currBoundNodeIdx >= relScanState.cachedBoundNodeSelVector.getSelSize()) {
            return false;
        }
        const auto boundNodePos =
            relScanState.cachedBoundNodeSelVector[relScanState.currBoundNodeIdx];
        const auto boundNodeOffset = relScanState.nodeIDVector->readNodeOffset(boundNodePos);
        auto& localCSRIndex =
            directedIndices[RelDirectionUtils::relDirectionToKeyIdx(relScanState.direction)].index;
        if (localScanState.rowIndices.empty() && localCSRIndex.contains(boundNodeOffset)) {
            localScanState.rowIndices = localCSRIndex.at(boundNodeOffset);
            localScanState.nextRowToScan = 0;
            KU_ASSERT(
                std::is_sorted(localScanState.rowIndices.begin(), localScanState.rowIndices.end()));
        }
        KU_ASSERT(localScanState.rowIndices.size() >= localScanState.nextRowToScan);
        const auto numToScan =
            std::min(localScanState.rowIndices.size() - localScanState.nextRowToScan,
                DEFAULT_VECTOR_CAPACITY);
        if (numToScan == 0) {
            relScanState.currBoundNodeIdx++;
            localScanState.nextRowToScan = 0;
            localScanState.rowIndices.clear();
            continue;
        }
        for (auto i = 0u; i < numToScan; i++) {
            localScanState.rowIdxVector->setValue<row_idx_t>(i,
                localScanState.rowIndices[localScanState.nextRowToScan + i]);
        }
        localScanState.rowIdxVector->state->getSelVectorUnsafe().setSelSize(numToScan);
        [[maybe_unused]] auto lookupRes =
            localNodeGroup->lookupMultiple(transaction, localScanState);
        localScanState.nextRowToScan += numToScan;
        relScanState.setNodeIDVectorToFlat(
            relScanState.cachedBoundNodeSelVector[relScanState.currBoundNodeIdx]);
        return true;
    }
}

static std::unique_ptr<RelTableScanState> setupLocalTableScanState(DataChunk& scanChunk,
    std::span<row_idx_t> intersectRows) {
    const std::vector columnIDs{LOCAL_REL_ID_COLUMN_ID};
    auto scanState = std::make_unique<RelTableScanState>(nullptr,
        std::vector{&scanChunk.getValueVectorMutable(0)}, scanChunk.state);
    scanState->columnIDs = columnIDs;
    scanState->nodeGroupScanState->chunkStates.resize(columnIDs.size());
    scanChunk.state->getSelVectorUnsafe().setSelSize(intersectRows.size());
    for (uint64_t i = 0; i < intersectRows.size(); i++) {
        scanState->rowIdxVector->setValue<row_idx_t>(i, intersectRows[i]);
    }
    return scanState;
}

row_idx_t LocalRelTable::findMatchingRow(const Transaction* transaction,
    const std::vector<row_idx_vec_t*>& rowIndicesToCheck, offset_t relOffset) const {
    for (auto* rowIndex : rowIndicesToCheck) {
        std::sort(rowIndex->begin(), rowIndex->end());
    }
    std::vector<row_idx_t> intersectRows =
        std::accumulate(rowIndicesToCheck.begin(), rowIndicesToCheck.end(), *rowIndicesToCheck[0],
            [](row_idx_vec_t curIntersection, row_idx_vec_t* rowIndex) -> row_idx_vec_t {
                row_idx_vec_t ret;
                std::set_intersection(curIntersection.begin(), curIntersection.end(),
                    rowIndex->begin(), rowIndex->end(), std::back_inserter(ret));
                return ret;
            });
    // Loop over relID column chunks to find the relID.
    const auto numVectorsToScan =
        ceilDiv(static_cast<uint64_t>(intersectRows.size()), DEFAULT_VECTOR_CAPACITY);
    for (uint64_t vectorIdx = 0; vectorIdx < numVectorsToScan; ++vectorIdx) {
        DataChunk scanChunk(1);
        scanChunk.insert(0, std::make_shared<ValueVector>(LogicalType::INTERNAL_ID()));

        const uint64_t startRowToScan = vectorIdx * DEFAULT_VECTOR_CAPACITY;
        const auto endRowToScan = std::min(startRowToScan + DEFAULT_VECTOR_CAPACITY,
            static_cast<uint64_t>(intersectRows.size()));
        std::span currentRowsToCheck{intersectRows.begin() + startRowToScan,
            intersectRows.begin() + endRowToScan};
        const auto scanState = setupLocalTableScanState(scanChunk, currentRowsToCheck);

        [[maybe_unused]] auto lookupRes = localNodeGroup->lookupMultiple(transaction, *scanState);
        const auto scannedRelIDVector = scanState->outputVectors[0];
        KU_ASSERT(
            scannedRelIDVector->state->getSelVector().getSelSize() == currentRowsToCheck.size());
        for (auto i = 0u; i < currentRowsToCheck.size(); i++) {
            if (scannedRelIDVector->getValue<internalID_t>(i).offset == relOffset) {
                return currentRowsToCheck[i];
            }
        }
    }
    return INVALID_ROW_IDX;
}

} // namespace storage
} // namespace lbug
