#include "expression_evaluator/function_evaluator.h"

#include "binder/expression/scalar_function_expression.h"
#include "function/sequence/sequence_functions.h"

using namespace lbug::common;
using namespace lbug::processor;
using namespace lbug::storage;
using namespace lbug::main;
using namespace lbug::binder;
using namespace lbug::function;

namespace lbug {
namespace evaluator {

FunctionExpressionEvaluator::FunctionExpressionEvaluator(std::shared_ptr<Expression> expression,
    std::vector<std::unique_ptr<ExpressionEvaluator>> children)
    : ExpressionEvaluator{type_, std::move(expression), std::move(children)} {
    auto& functionExpr = this->expression->constCast<ScalarFunctionExpression>();
    function = functionExpr.getFunction().copy();
    bindData = functionExpr.getBindData()->copy();
}

void FunctionExpressionEvaluator::evaluate() {
    auto ctx = localState.clientContext;
    for (auto& child : children) {
        child->evaluate();
    }
    if (function->execFunc != nullptr) {
        bindData->clientContext = ctx;
        runExecFunc(bindData.get());
    }
}

void FunctionExpressionEvaluator::evaluate(common::sel_t count) {
    KU_ASSERT(expression->constCast<ScalarFunctionExpression>().getFunction().name ==
              NextValFunction::name);
    for (auto& child : children) {
        child->evaluate(count);
    }
    bindData->count = count;
    bindData->clientContext = localState.clientContext;
    runExecFunc(bindData.get());
}

bool FunctionExpressionEvaluator::selectInternal(SelectionVector& selVector) {
    for (auto& child : children) {
        child->evaluate();
    }
    // Temporary code path for function whose return type is BOOL but select interface is not
    // implemented (e.g. list_contains). We should remove this if statement eventually.
    if (function->selectFunc == nullptr) {
        KU_ASSERT(resultVector->dataType.getLogicalTypeID() == LogicalTypeID::BOOL);
        runExecFunc();
        return updateSelectedPos(selVector);
    }
    return function->selectFunc(parameters, selVector, bindData.get());
}

void FunctionExpressionEvaluator::runExecFunc(void* dataPtr) {
    function->execFunc(parameters, common::SelectionVector::fromValueVectors(parameters),
        *resultVector, resultVector->getSelVectorPtr(), dataPtr);
}

void FunctionExpressionEvaluator::resolveResultVector(const ResultSet& /*resultSet*/,
    MemoryManager* memoryManager) {
    resultVector = std::make_shared<ValueVector>(expression->dataType.copy(), memoryManager);
    std::vector<ExpressionEvaluator*> inputEvaluators;
    inputEvaluators.reserve(children.size());
    for (auto& child : children) {
        parameters.push_back(child->resultVector);
        inputEvaluators.push_back(child.get());
    }
    resolveResultStateFromChildren(inputEvaluators);
    if (function->compileFunc != nullptr) {
        function->compileFunc(bindData.get(), parameters, resultVector);
    }
}

} // namespace evaluator
} // namespace lbug
