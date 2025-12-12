#pragma once

#include "common/types/value/value.h"
#include "expression.h"

namespace lbug {
namespace binder {

class LBUG_API LiteralExpression final : public Expression {
    static constexpr common::ExpressionType type_ = common::ExpressionType::LITERAL;

public:
    LiteralExpression(common::Value value, const std::string& uniqueName)
        : Expression{type_, value.getDataType().copy(), uniqueName}, value{std::move(value)} {}

    bool isNull() const { return value.isNull(); }

    void cast(const common::LogicalType& type) override;

    common::Value getValue() const { return value; }

    std::string toStringInternal() const override { return value.toString(); }

public:
    common::Value value;
};

} // namespace binder
} // namespace lbug
