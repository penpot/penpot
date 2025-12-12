#pragma once

#include "expression.h"
#include "function/scalar_function.h"

namespace lbug {
namespace binder {

class ScalarFunctionExpression final : public Expression {
public:
    ScalarFunctionExpression(common::ExpressionType expressionType,
        std::unique_ptr<function::ScalarFunction> function,
        std::unique_ptr<function::FunctionBindData> bindData, expression_vector children,
        std::string uniqueName)
        : Expression{expressionType, bindData->resultType.copy(), std::move(children),
              std::move(uniqueName)},
          function{std::move(function)}, bindData{std::move(bindData)} {}

    const function::ScalarFunction& getFunction() const { return *function; }
    function::FunctionBindData* getBindData() const { return bindData.get(); }

    std::string toStringInternal() const override;

    static std::string getUniqueName(const std::string& functionName,
        const expression_vector& children);

private:
    std::unique_ptr<function::ScalarFunction> function;
    std::unique_ptr<function::FunctionBindData> bindData;
};

} // namespace binder
} // namespace lbug
