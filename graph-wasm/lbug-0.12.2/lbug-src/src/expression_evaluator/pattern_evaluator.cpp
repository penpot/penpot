#include "expression_evaluator/pattern_evaluator.h"

#include "common/constants.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::storage;
using namespace lbug::main;
using namespace lbug::common;
using namespace lbug::function;
using namespace lbug::processor;

namespace lbug {
namespace evaluator {

static void updateNullPattern(ValueVector& patternVector, const ValueVector& idVector) {
    // If internal id is NULL, we should mark entire struct/node/rel as NULL.
    for (auto i = 0u; i < patternVector.state->getSelVector().getSelSize(); ++i) {
        auto pos = patternVector.state->getSelVector()[i];
        patternVector.setNull(pos, idVector.isNull(pos));
    }
}

void PatternExpressionEvaluator::evaluate() {
    for (auto& child : children) {
        child->evaluate();
    }
    StructPackFunctions::execFunc(parameters, SelectionVector::fromValueVectors(parameters),
        *resultVector, resultVector->getSelVectorPtr());
    updateNullPattern(*resultVector, *idVector);
}

void PatternExpressionEvaluator::resolveResultVector(const ResultSet& resultSet,
    MemoryManager* memoryManager) {
    const auto& dataType = expression->getDataType();
    resultVector = std::make_shared<ValueVector>(dataType.copy(), memoryManager);
    std::vector<ExpressionEvaluator*> inputEvaluators;
    inputEvaluators.reserve(children.size());
    for (auto& child : children) {
        parameters.push_back(child->resultVector);
        inputEvaluators.push_back(child.get());
    }
    resolveResultStateFromChildren(inputEvaluators);
    initFurther(resultSet);
}

void PatternExpressionEvaluator::initFurther(const ResultSet&) {
    StructPackFunctions::compileFunc(nullptr, parameters, resultVector);
    const auto& dataType = expression->getDataType();
    auto fieldIdx = StructType::getFieldIdx(dataType.copy(), InternalKeyword::ID);
    KU_ASSERT(fieldIdx != INVALID_STRUCT_FIELD_IDX);
    idVector = StructVector::getFieldVector(resultVector.get(), fieldIdx).get();
}

void UndirectedRelExpressionEvaluator::evaluate() {
    for (auto& child : children) {
        child->evaluate();
    }
    StructPackFunctions::undirectedRelPackExecFunc(parameters, *resultVector);
    updateNullPattern(*resultVector, *idVector);
    directionEvaluator->evaluate();
    auto& selVector = resultVector->state->getSelVector();
    for (auto i = 0u; i < selVector.getSelSize(); ++i) {
        if (!directionVector->getValue<bool>(directionVector->state->getSelVector()[i])) {
            continue;
        }
        auto pos = selVector[i];
        auto srcID = srcIDVector->getValue<internalID_t>(pos);
        auto dstID = dstIDVector->getValue<internalID_t>(pos);
        srcIDVector->setValue(pos, dstID);
        dstIDVector->setValue(pos, srcID);
    }
}

void UndirectedRelExpressionEvaluator::initFurther(const ResultSet& resultSet) {
    directionEvaluator->init(resultSet, localState.clientContext);
    directionVector = directionEvaluator->resultVector.get();
    StructPackFunctions::undirectedRelCompileFunc(nullptr, parameters, resultVector);
    const auto& dataType = expression->getDataType();
    auto idFieldIdx = StructType::getFieldIdx(dataType, InternalKeyword::ID);
    auto srcFieldIdx = StructType::getFieldIdx(dataType, InternalKeyword::SRC);
    auto dstFieldIdx = StructType::getFieldIdx(dataType, InternalKeyword::DST);
    idVector = StructVector::getFieldVector(resultVector.get(), idFieldIdx).get();
    srcIDVector = StructVector::getFieldVector(resultVector.get(), srcFieldIdx).get();
    dstIDVector = StructVector::getFieldVector(resultVector.get(), dstFieldIdx).get();
}

} // namespace evaluator
} // namespace lbug
