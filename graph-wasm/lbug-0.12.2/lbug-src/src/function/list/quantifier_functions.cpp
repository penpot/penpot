#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "expression_evaluator/lambda_evaluator.h"
#include "function/function.h"
#include "function/list/vector_list_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void execQuantifierFunc(quantifier_handler handler,
    const std::vector<std::shared_ptr<common::ValueVector>>& input,
    const std::vector<common::SelectionVector*>& inputSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* bindData) {
    auto listLambdaBindData = reinterpret_cast<evaluator::ListLambdaBindData*>(bindData);
    auto& inputVector = *input[0];
    if (!listLambdaBindData->lambdaParamEvaluators.empty()) {
        auto listSize = ListVector::getDataVectorSize(&inputVector);
        auto lambdaParamVector = listLambdaBindData->lambdaParamEvaluators[0]->resultVector.get();
        lambdaParamVector->state->getSelVectorUnsafe().setSelSize(listSize);
    }
    auto& filterVector = *input[1];
    bool isConstantTrueExpr = listLambdaBindData->lambdaParamEvaluators.empty() &&
                              filterVector.getValue<bool>(filterVector.state->getSelVector()[0]);
    listLambdaBindData->rootEvaluator->evaluate();
    KU_ASSERT(input.size() == 2);
    auto& listInputSelVector = *inputSelVectors[0];
    uint64_t numSelectedValues = 0;
    for (auto i = 0u; i < listInputSelVector.getSelSize(); ++i) {
        numSelectedValues = 0;
        auto srcListEntry = inputVector.getValue<list_entry_t>(listInputSelVector[i]);
        for (auto j = 0u; j < srcListEntry.size; j++) {
            auto pos = srcListEntry.offset + j;
            if (isConstantTrueExpr || filterVector.getValue<bool>(pos)) {
                numSelectedValues++;
            }
        }
        result.setValue((*resultSelVector)[i], handler(numSelectedValues, srcListEntry.size));
    }
}

std::unique_ptr<FunctionBindData> bindQuantifierFunc(const ScalarBindFuncInput& input) {
    std::vector<common::LogicalType> paramTypes;
    paramTypes.push_back(input.arguments[0]->getDataType().copy());
    paramTypes.push_back(input.arguments[1]->getDataType().copy());
    return std::make_unique<FunctionBindData>(std::move(paramTypes), common::LogicalType::BOOL());
}

} // namespace function
} // namespace lbug
