#pragma once

#include "common/string_utils.h"
#include "parsed_expression.h"

namespace lbug {
namespace parser {

class ParsedFunctionExpression : public ParsedExpression {
    static constexpr common::ExpressionType expressionType_ = common::ExpressionType::FUNCTION;

public:
    ParsedFunctionExpression(std::string functionName, std::string rawName, bool isDistinct = false)
        : ParsedExpression{expressionType_, std::move(rawName)}, isDistinct{isDistinct},
          functionName{std::move(functionName)} {}

    ParsedFunctionExpression(std::string functionName, std::unique_ptr<ParsedExpression> child,
        std::string rawName, bool isDistinct = false)
        : ParsedExpression{expressionType_, std::move(child), std::move(rawName)},
          isDistinct{isDistinct}, functionName{std::move(functionName)} {}

    ParsedFunctionExpression(std::string functionName, std::unique_ptr<ParsedExpression> left,
        std::unique_ptr<ParsedExpression> right, std::string rawName, bool isDistinct = false)
        : ParsedExpression{expressionType_, std::move(left), std::move(right), std::move(rawName)},
          isDistinct{isDistinct}, functionName{std::move(functionName)} {}

    ParsedFunctionExpression(std::string alias, std::string rawName, parsed_expr_vector children,
        std::string functionName, bool isDistinct, std::vector<std::string> optionalArguments)
        : ParsedExpression{expressionType_, std::move(alias), std::move(rawName),
              std::move(children)},
          isDistinct{isDistinct}, functionName{std::move(functionName)},
          optionalArguments{std::move(optionalArguments)} {}

    ParsedFunctionExpression(std::string functionName, bool isDistinct)
        : ParsedExpression{expressionType_}, isDistinct{isDistinct},
          functionName{std::move(functionName)} {}

    bool getIsDistinct() const { return isDistinct; }

    std::string getFunctionName() const { return functionName; }
    std::string getNormalizedFunctionName() const {
        return common::StringUtils::getUpper(functionName);
    }

    void addChild(std::unique_ptr<ParsedExpression> child) { children.push_back(std::move(child)); }

    void setOptionalArguments(std::vector<std::string> optionalArguments) {
        this->optionalArguments = std::move(optionalArguments);
    }
    void addOptionalParams(std::string name, std::unique_ptr<ParsedExpression> child) {
        optionalArguments.push_back(std::move(name));
        children.push_back(std::move(child));
    }

    const std::vector<std::string>& getOptionalArguments() const { return optionalArguments; }

    static std::unique_ptr<ParsedFunctionExpression> deserialize(
        common::Deserializer& deserializer);

    std::unique_ptr<ParsedExpression> copy() const override {
        return std::make_unique<ParsedFunctionExpression>(alias, rawName, copyVector(children),
            functionName, isDistinct, optionalArguments);
    }

private:
    void serializeInternal(common::Serializer& serializer) const override;

private:
    bool isDistinct;
    std::string functionName;
    // In Lbug, function arguments must be either all required or all optional - mixing required and
    // optional parameters in the same function is not allowed.
    std::vector<std::string> optionalArguments;
};

} // namespace parser
} // namespace lbug
