#include "processor/operator/scan/scan_rel_table.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"
#include "storage/local_storage/local_rel_table.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string ScanRelTablePrintInfo::toString() const {
    std::string result = "Tables: ";
    for (auto& tableName : tableNames) {
        result += tableName;
        if (tableName != tableNames.back()) {
            result += ", ";
        }
    }
    if (!alias.empty()) {
        result += ",Alias: ";
        result += alias;
    }
    result += ",Direction: (";
    result += boundNode->toString();
    result += ")";
    switch (direction) {
    case ExtendDirection::FWD: {
        result += "-[";
        result += rel->detailsToString();
        result += "]->";
    } break;
    case ExtendDirection::BWD: {
        result += "<-[";
        result += rel->detailsToString();
        result += "]-";
    } break;
    case ExtendDirection::BOTH: {
        result += "<-[";
        result += rel->detailsToString();
        result += "]->";
    } break;
    default:
        KU_UNREACHABLE;
    }
    result += "(";
    result += nbrNode->toString();
    result += ")";
    if (!properties.empty()) {
        result += ",Properties: ";
        result += binder::ExpressionUtil::toString(properties);
    }
    return result;
}

void ScanRelTableInfo::initScanState(TableScanState& scanState,
    const std::vector<ValueVector*>& outVectors, main::ClientContext* context) {
    auto transaction = transaction::Transaction::Get(*context);
    scanState.setToTable(transaction, table, columnIDs, copyVector(columnPredicates), direction);
    initScanStateVectors(scanState, outVectors, MemoryManager::Get(*context));
}

void ScanRelTable::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    ScanTable::initLocalStateInternal(resultSet, context);
    auto clientContext = context->clientContext;
    auto boundNodeIDVector = resultSet->getValueVector(opInfo.nodeIDPos).get();
    auto nbrNodeIDVector = outVectors[0];
    scanState = std::make_unique<RelTableScanState>(*MemoryManager::Get(*clientContext),
        boundNodeIDVector, outVectors, nbrNodeIDVector->state);
    tableInfo.initScanState(*scanState, outVectors, clientContext);
}

bool ScanRelTable::getNextTuplesInternal(ExecutionContext* context) {
    const auto transaction = transaction::Transaction::Get(*context->clientContext);
    while (true) {
        while (tableInfo.table->scan(transaction, *scanState)) {
            const auto outputSize = scanState->outState->getSelVector().getSelSize();
            if (outputSize > 0) {
                // No need to perform column cast because this is single table scan.
                metrics->numOutputTuple.increase(outputSize);
                return true;
            }
        }
        if (!children[0]->getNextTuple(context)) {
            return false;
        }
        tableInfo.table->initScanState(transaction, *scanState);
    }
}

} // namespace processor
} // namespace lbug
