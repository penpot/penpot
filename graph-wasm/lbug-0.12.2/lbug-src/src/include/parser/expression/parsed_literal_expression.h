#pragma once

#include "common/types/value/value.h"
#include "parsed_expression.h"

namespace lbug {
namespace parser {

class ParsedLiteralExpression : public ParsedExpression {
    static constexpr common::ExpressionType expressionType = common::ExpressionType::LITERAL;

public:
    ParsedLiteralExpression(common::Value value, std::string raw)
        : ParsedExpression{expressionType, std::move(raw)}, value{std::move(value)} {}

    ParsedLiteralExpression(std::string alias, std::string rawName, parsed_expr_vector children,
        common::Value value)
        : ParsedExpression{expressionType, std::move(alias), std::move(rawName),
              std::move(children)},
          value{std::move(value)} {}

    explicit ParsedLiteralExpression(common::Value value)
        : ParsedExpression{expressionType}, value{std::move(value)} {}

    common::Value getValue() const { return value; }

    static std::unique_ptr<ParsedLiteralExpression> deserialize(
        common::Deserializer& deserializer) {
        return std::make_unique<ParsedLiteralExpression>(*common::Value::deserialize(deserializer));
    }

    std::unique_ptr<ParsedExpression> copy() const override {
        return std::make_unique<ParsedLiteralExpression>(alias, rawName, copyVector(children),
            value);
    }

private:
    void serializeInternal(common::Serializer& serializer) const override {
        value.serialize(serializer);
    }

private:
    common::Value value;
};

} // namespace parser
} // namespace lbug
