#include "parser/expression/parsed_expression.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "parser/expression/parsed_case_expression.h"
#include "parser/expression/parsed_function_expression.h"
#include "parser/expression/parsed_literal_expression.h"
#include "parser/expression/parsed_parameter_expression.h"
#include "parser/expression/parsed_property_expression.h"
#include "parser/expression/parsed_subquery_expression.h"
#include "parser/expression/parsed_variable_expression.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

ParsedExpression::ParsedExpression(ExpressionType type, std::unique_ptr<ParsedExpression> child,
    std::string rawName)
    : type{type}, rawName{std::move(rawName)} {
    children.push_back(std::move(child));
}

ParsedExpression::ParsedExpression(ExpressionType type, std::unique_ptr<ParsedExpression> left,
    std::unique_ptr<ParsedExpression> right, std::string rawName)
    : type{type}, rawName{std::move(rawName)} {
    children.push_back(std::move(left));
    children.push_back(std::move(right));
}

void ParsedExpression::serialize(Serializer& serializer) const {
    serializer.serializeValue(type);
    serializer.serializeValue(alias);
    serializer.serializeValue(rawName);
    serializer.serializeVectorOfPtrs(children);
    serializeInternal(serializer);
}

std::unique_ptr<ParsedExpression> ParsedExpression::deserialize(Deserializer& deserializer) {
    auto type = ExpressionType::INVALID;
    std::string alias;
    std::string rawName;
    parsed_expr_vector children;
    deserializer.deserializeValue(type);
    deserializer.deserializeValue(alias);
    deserializer.deserializeValue(rawName);
    deserializer.deserializeVectorOfPtrs(children);
    std::unique_ptr<ParsedExpression> parsedExpression;
    switch (type) {
    case ExpressionType::CASE_ELSE: {
        parsedExpression = ParsedCaseExpression::deserialize(deserializer);
    } break;
    case ExpressionType::FUNCTION: {
        parsedExpression = ParsedFunctionExpression::deserialize(deserializer);
    } break;
    case ExpressionType::LITERAL: {
        parsedExpression = ParsedLiteralExpression::deserialize(deserializer);
    } break;
    case ExpressionType::PARAMETER: {
        parsedExpression = ParsedParameterExpression::deserialize(deserializer);
    } break;
    case ExpressionType::PROPERTY: {
        parsedExpression = ParsedPropertyExpression::deserialize(deserializer);
    } break;
    case ExpressionType::SUBQUERY: {
        parsedExpression = ParsedSubqueryExpression::deserialize(deserializer);
    } break;
    case ExpressionType::VARIABLE: {
        parsedExpression = ParsedVariableExpression::deserialize(deserializer);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    parsedExpression->alias = std::move(alias);
    parsedExpression->rawName = std::move(rawName);
    parsedExpression->children = std::move(children);
    return parsedExpression;
}

} // namespace parser
} // namespace lbug
