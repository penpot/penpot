#include "binder/expression/expression_util.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression_binder.h"
#include "common/types/value/value.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/path/vector_path_functions.h"
#include "function/rewrite_function.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace function {

static std::shared_ptr<Expression> rewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto param = input.arguments[0].get();
    auto binder = input.expressionBinder;
    if (param->expressionType == ExpressionType::PATH) {
        int64_t numRels = 0u;
        std::vector<const RelExpression*> recursiveRels;
        for (auto& child : param->getChildren()) {
            if (ExpressionUtil::isRelPattern(*child)) {
                numRels++;
            } else if (ExpressionUtil::isRecursiveRelPattern(*child)) {
                recursiveRels.push_back(child->constPtrCast<RelExpression>());
            }
        }
        auto numRelsExpression = binder->createLiteralExpression(Value(numRels));
        if (recursiveRels.empty()) {
            return numRelsExpression;
        }
        expression_vector children;
        children.push_back(std::move(numRelsExpression));
        children.push_back(recursiveRels[0]->getLengthExpression());
        auto result = binder->bindScalarFunctionExpression(children, AddFunction::name);
        for (auto i = 1u; i < recursiveRels.size(); ++i) {
            children[0] = std::move(result);
            children[1] = recursiveRels[i]->getLengthExpression();
            result = binder->bindScalarFunctionExpression(children, AddFunction::name);
        }
        return result;
    } else if (ExpressionUtil::isRecursiveRelPattern(*param)) {
        return param->constPtrCast<RelExpression>()->getLengthExpression();
    }
    KU_UNREACHABLE;
}

function_set LengthFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<RewriteFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::RECURSIVE_REL}, rewriteFunc);
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
