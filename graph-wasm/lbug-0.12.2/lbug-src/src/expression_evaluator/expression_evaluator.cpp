#include "expression_evaluator/expression_evaluator.h"

#include "common/exception/runtime.h"
#include "main/client_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace evaluator {

void ExpressionEvaluator::init(const processor::ResultSet& resultSet,
    main::ClientContext* clientContext) {
    localState.clientContext = clientContext;
    for (auto& child : children) {
        child->init(resultSet, clientContext);
    }
    resolveResultVector(resultSet, storage::MemoryManager::Get(*clientContext));
}

void ExpressionEvaluator::resolveResultStateFromChildren(
    const std::vector<ExpressionEvaluator*>& inputEvaluators) {
    if (resultVector->state != nullptr) {
        return;
    }
    for (auto& input : inputEvaluators) {
        if (!input->isResultFlat()) {
            isResultFlat_ = false;
            resultVector->setState(input->resultVector->state);
            return;
        }
    }
    // All children are flat.
    isResultFlat_ = true;
    // We need to leave capacity for multiple evaluations
    resultVector->setState(std::make_shared<DataChunkState>());
    resultVector->state->initOriginalAndSelectedSize(1);
    resultVector->state->setToFlat();
}

void ExpressionEvaluator::evaluate(common::sel_t) {
    // LCOV_EXCL_START
    throw RuntimeException(stringFormat("Cannot evaluate expression {} with count. This should "
                                        "never happen.",
        expression->toString()));
    // LCOV_EXCL_STOP
}

bool ExpressionEvaluator::select(common::SelectionVector& selVector,
    bool shouldSetSelVectorToFiltered) {
    bool ret = selectInternal(selVector);
    if (shouldSetSelVectorToFiltered && selVector.isUnfiltered()) {
        selVector.setToFiltered();
    }
    return ret;
}

} // namespace evaluator
} // namespace lbug
