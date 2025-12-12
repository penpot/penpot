#include "processor/operator/scan/primary_key_scan_node_table.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string PrimaryKeyScanPrintInfo::toString() const {
    std::string result = "Key: ";
    result += key;
    if (!alias.empty()) {
        result += ",Alias: ";
        result += alias;
    }
    result += ", Expressions: ";
    result += binder::ExpressionUtil::toString(expressions);
    return result;
}

idx_t PrimaryKeyScanSharedState::getTableIdx() {
    std::unique_lock lck{mtx};
    if (cursor < numTables) {
        return cursor++;
    }
    return numTables;
}

void PrimaryKeyScanNodeTable::initLocalStateInternal(ResultSet* resultSet,
    ExecutionContext* context) {
    ScanTable::initLocalStateInternal(resultSet, context);
    auto nodeIDVector = resultSet->getValueVector(opInfo.nodeIDPos).get();
    scanState = std::make_unique<NodeTableScanState>(nodeIDVector, std::vector<ValueVector*>{},
        nodeIDVector->state);
    indexEvaluator->init(*resultSet, context->clientContext);
}

bool PrimaryKeyScanNodeTable::getNextTuplesInternal(ExecutionContext* context) {
    auto transaction = transaction::Transaction::Get(*context->clientContext);
    auto tableIdx = sharedState->getTableIdx();
    if (tableIdx >= tableInfos.size()) {
        return false;
    }
    KU_ASSERT(tableIdx < tableInfos.size());
    auto& tableInfo = tableInfos[tableIdx];
    // Look up index
    indexEvaluator->evaluate();
    auto indexVector = indexEvaluator->resultVector.get();
    auto& selVector = indexVector->state->getSelVector();
    KU_ASSERT(selVector.getSelSize() == 1);
    auto pos = selVector.getSelectedPositions()[0];
    if (indexVector->isNull(pos)) {
        return false;
    }
    offset_t nodeOffset = 0;
    auto& table = tableInfo.table->cast<NodeTable>();
    if (!table.lookupPK(transaction, indexVector, pos, nodeOffset)) {
        return false;
    }
    auto nodeID = nodeID_t{nodeOffset, table.getTableID()};
    scanState->nodeIDVector->setValue<nodeID_t>(pos, nodeID);
    // Look up properties
    tableInfo.initScanState(*scanState, outVectors, context->clientContext);
    table.initScanState(transaction, *scanState, nodeID.tableID, nodeOffset);
    auto succeeded = table.lookup(transaction, *scanState);
    tableInfo.castColumns();
    metrics->numOutputTuple.incrementByOne();
    return succeeded;
}

} // namespace processor
} // namespace lbug
