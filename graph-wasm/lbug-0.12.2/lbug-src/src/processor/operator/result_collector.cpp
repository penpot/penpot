#include "processor/operator/result_collector.h"

#include "binder/expression/expression_util.h"
#include "main/query_result/materialized_query_result.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string ResultCollectorPrintInfo::toString() const {
    std::string result = "";
    if (accumulateType == AccumulateType::OPTIONAL_) {
        result += "Type: " + AccumulateTypeUtil::toString(accumulateType);
    }
    result += ",Expressions: ";
    result += binder::ExpressionUtil::toString(expressions);
    return result;
}

void ResultCollector::initNecessaryLocalState(ResultSet* resultSet, ExecutionContext* context) {
    payloadVectors.reserve(info.payloadPositions.size());
    for (auto& pos : info.payloadPositions) {
        auto vec = resultSet->getValueVector(pos).get();
        payloadVectors.push_back(vec);
        payloadAndMarkVectors.push_back(vec);
    }
    if (info.accumulateType == AccumulateType::OPTIONAL_) {
        markVector = std::make_unique<ValueVector>(LogicalType::BOOL(),
            MemoryManager::Get(*context->clientContext));
        markVector->state = DataChunkState::getSingleValueDataChunkState();
        markVector->setValue<bool>(0, true);
        payloadAndMarkVectors.push_back(markVector.get());
    }
}

void ResultCollector::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    initNecessaryLocalState(resultSet, context);
    localTable = std::make_unique<FactorizedTable>(MemoryManager::Get(*context->clientContext),
        info.tableSchema.copy());
}

void ResultCollector::executeInternal(ExecutionContext* context) {
    while (children[0]->getNextTuple(context)) {
        if (!payloadVectors.empty()) {
            for (auto i = 0u; i < resultSet->multiplicity; i++) {
                localTable->append(payloadAndMarkVectors);
            }
        }
    }
    if (!payloadVectors.empty()) {
        metrics->numOutputTuple.increase(localTable->getTotalNumFlatTuples());
        sharedState->mergeLocalTable(*localTable);
    }
}

void ResultCollector::finalizeInternal(ExecutionContext* context) {
    switch (info.accumulateType) {
    case AccumulateType::OPTIONAL_: {
        auto localResultSet = getResultSet(MemoryManager::Get(*context->clientContext));
        initNecessaryLocalState(localResultSet.get(), context);
        // We should remove currIdx completely as some of the code still relies on currIdx = -1 to
        // check if the state if unFlat or not. This should no longer be necessary.
        // TODO(Ziyi): add an interface in factorized table
        auto table = sharedState->getTable();
        auto tableSchema = table->getTableSchema();
        for (auto i = 0u; i < payloadVectors.size(); ++i) {
            auto columnSchema = tableSchema->getColumn(i);
            if (columnSchema->isFlat()) {
                payloadVectors[i]->state->setToFlat();
            }
        }
        if (table->isEmpty()) {
            for (auto& vector : payloadVectors) {
                vector->setAsSingleNullEntry();
            }
            markVector->setValue<bool>(0, false);
            table->append(payloadAndMarkVectors);
        }
    }
    default:
        break;
    }
}

std::unique_ptr<main::QueryResult> ResultCollector::getQueryResult() const {
    return std::make_unique<main::MaterializedQueryResult>(sharedState->getTable());
}

} // namespace processor
} // namespace lbug
