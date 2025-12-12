#include "expression_evaluator/case_evaluator.h"

using namespace lbug::main;
using namespace lbug::common;
using namespace lbug::processor;
using namespace lbug::storage;

namespace lbug {
namespace evaluator {

void CaseAlternativeEvaluator::init(const ResultSet& resultSet,
    main::ClientContext* clientContext) {
    whenEvaluator->init(resultSet, clientContext);
    thenEvaluator->init(resultSet, clientContext);
    whenSelVector = std::make_unique<SelectionVector>(DEFAULT_VECTOR_CAPACITY);
    whenSelVector->setToFiltered();
}

void CaseExpressionEvaluator::init(const ResultSet& resultSet, main::ClientContext* clientContext) {
    for (auto& alternativeEvaluator : alternativeEvaluators) {
        alternativeEvaluator.init(resultSet, clientContext);
    }
    elseEvaluator->init(resultSet, clientContext);
    ExpressionEvaluator::init(resultSet, clientContext);
}

void CaseExpressionEvaluator::evaluate() {
    filledMask.reset();
    for (auto& alternativeEvaluator : alternativeEvaluators) {
        auto whenSelVector = alternativeEvaluator.whenSelVector.get();
        // the sel vector is already set to filtered in init()
        auto hasAtLeastOneValue = alternativeEvaluator.whenEvaluator->select(*whenSelVector, false);
        if (!hasAtLeastOneValue) {
            continue;
        }
        alternativeEvaluator.thenEvaluator->evaluate();
        auto thenVector = alternativeEvaluator.thenEvaluator->resultVector.get();
        if (alternativeEvaluator.whenEvaluator->isResultFlat()) {
            fillAll(thenVector);
        } else {
            fillSelected(*whenSelVector, thenVector);
        }
        if (filledMask.count() == resultVector->state->getSelVector().getSelSize()) {
            return;
        }
    }
    elseEvaluator->evaluate();
    fillAll(elseEvaluator->resultVector.get());
}

bool CaseExpressionEvaluator::selectInternal(SelectionVector& selVector) {
    evaluate();
    KU_ASSERT(resultVector->state->getSelVector().getSelSize() != 0);
    KU_ASSERT(selVector.getSelSize() != 0);
    KU_ASSERT(resultVector->state->getSelVector().getSelSize() == selVector.getSelSize());
    auto numSelectedValues = 0u;
    auto selectedPosBuffer = selVector.getMutableBuffer();
    for (auto i = 0u; i < selVector.getSelSize(); ++i) {
        auto selVectorPos = selVector[i];
        auto resultVectorPos = resultVector->state->getSelVector()[i];
        selectedPosBuffer[numSelectedValues] = selVectorPos;
        const bool selectCurrentValue =
            !resultVector->isNull(resultVectorPos) && resultVector->getValue<bool>(resultVectorPos);
        numSelectedValues += selectCurrentValue;
    }
    selVector.setSelSize(numSelectedValues);
    return numSelectedValues > 0;
}

void CaseExpressionEvaluator::resolveResultVector(const ResultSet& /*resultSet*/,
    MemoryManager* memoryManager) {
    resultVector = std::make_shared<ValueVector>(expression->dataType.copy(), memoryManager);
    std::vector<ExpressionEvaluator*> inputEvaluators;
    for (auto& alternative : alternativeEvaluators) {
        inputEvaluators.push_back(alternative.whenEvaluator.get());
        inputEvaluators.push_back(alternative.thenEvaluator.get());
    }
    inputEvaluators.push_back(elseEvaluator.get());
    resolveResultStateFromChildren(inputEvaluators);
}

void CaseExpressionEvaluator::fillSelected(const SelectionVector& selVector,
    ValueVector* srcVector) {
    for (auto i = 0u; i < selVector.getSelSize(); ++i) {
        auto resultPos = selVector[i];
        fillEntry(resultPos, srcVector);
    }
}

void CaseExpressionEvaluator::fillAll(ValueVector* srcVector) {
    auto& resultSelVector = resultVector->state->getSelVector();
    for (auto i = 0u; i < resultSelVector.getSelSize(); ++i) {
        auto resultPos = resultSelVector[i];
        fillEntry(resultPos, srcVector);
    }
}

void CaseExpressionEvaluator::fillEntry(sel_t resultPos, ValueVector* srcVector) {
    if (filledMask[resultPos]) {
        return;
    }
    filledMask[resultPos] = true;
    auto srcPos = srcVector->state->isFlat() ? srcVector->state->getSelVector()[0] : resultPos;
    resultVector->copyFromVectorData(resultPos, srcVector, srcPos);
}

} // namespace evaluator
} // namespace lbug
