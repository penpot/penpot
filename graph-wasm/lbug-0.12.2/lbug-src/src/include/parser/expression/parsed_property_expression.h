#pragma once

#include "common/constants.h"
#include "common/serializer/serializer.h"
#include "parsed_expression.h"

namespace lbug {
namespace parser {

class ParsedPropertyExpression : public ParsedExpression {
    static constexpr common::ExpressionType expressionType_ = common::ExpressionType::PROPERTY;

public:
    ParsedPropertyExpression(std::string propertyName, std::unique_ptr<ParsedExpression> child,
        std::string raw)
        : ParsedExpression{expressionType_, std::move(child), std::move(raw)},
          propertyName{std::move(propertyName)} {}

    ParsedPropertyExpression(std::string alias, std::string rawName, parsed_expr_vector children,
        std::string propertyName)
        : ParsedExpression{expressionType_, std::move(alias), std::move(rawName),
              std::move(children)},
          propertyName{std::move(propertyName)} {}

    explicit ParsedPropertyExpression(std::string propertyName)
        : ParsedExpression{expressionType_}, propertyName{std::move(propertyName)} {}

    std::string getPropertyName() const { return propertyName; }
    bool isStar() const { return propertyName == common::InternalKeyword::STAR; }

    static std::unique_ptr<ParsedPropertyExpression> deserialize(
        common::Deserializer& deserializer);

    std::unique_ptr<ParsedExpression> copy() const override {
        return std::make_unique<ParsedPropertyExpression>(alias, rawName, copyVector(children),
            propertyName);
    }

private:
    void serializeInternal(common::Serializer& serializer) const override {
        serializer.serializeValue(propertyName);
    }

private:
    std::string propertyName;
};

} // namespace parser
} // namespace lbug
