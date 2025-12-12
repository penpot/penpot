#pragma once

#include "common/types/value/value.h"
#include "expression.h"

namespace lbug {
namespace binder {

class LBUG_API ParameterExpression final : public Expression {
    static constexpr common::ExpressionType expressionType = common::ExpressionType::PARAMETER;

public:
    explicit ParameterExpression(const std::string& parameterName, common::Value value)
        : Expression{expressionType, value.getDataType().copy(), createUniqueName(parameterName)},
          parameterName(parameterName), value{std::move(value)} {}

    void cast(const common::LogicalType& type) override;

    common::Value getValue() const { return value; }

private:
    std::string toStringInternal() const override { return "$" + parameterName; }
    static std::string createUniqueName(const std::string& input) { return "$" + input; }

private:
    std::string parameterName;
    common::Value value;
};

} // namespace binder
} // namespace lbug
