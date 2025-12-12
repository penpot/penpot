#include "common/enums/expression_type.h"

#include "common/assert.h"
#include "function/comparison/vector_comparison_functions.h"

using namespace lbug::function;

namespace lbug {
namespace common {

bool ExpressionTypeUtil::isUnary(ExpressionType type) {
    return ExpressionType::NOT == type || ExpressionType::IS_NULL == type ||
           ExpressionType::IS_NOT_NULL == type;
}

bool ExpressionTypeUtil::isBinary(ExpressionType type) {
    return isComparison(type) || ExpressionType::OR == type || ExpressionType::XOR == type ||
           ExpressionType::AND == type;
}

bool ExpressionTypeUtil::isBoolean(ExpressionType type) {
    return ExpressionType::OR == type || ExpressionType::XOR == type ||
           ExpressionType::AND == type || ExpressionType::NOT == type;
}

bool ExpressionTypeUtil::isComparison(ExpressionType type) {
    return ExpressionType::EQUALS == type || ExpressionType::NOT_EQUALS == type ||
           ExpressionType::GREATER_THAN == type || ExpressionType::GREATER_THAN_EQUALS == type ||
           ExpressionType::LESS_THAN == type || ExpressionType::LESS_THAN_EQUALS == type;
}

bool ExpressionTypeUtil::isNullOperator(ExpressionType type) {
    return ExpressionType::IS_NULL == type || ExpressionType::IS_NOT_NULL == type;
}

ExpressionType ExpressionTypeUtil::reverseComparisonDirection(ExpressionType type) {
    KU_ASSERT(isComparison(type));
    switch (type) {
    case ExpressionType::GREATER_THAN:
        return ExpressionType::LESS_THAN;
    case ExpressionType::GREATER_THAN_EQUALS:
        return ExpressionType::LESS_THAN_EQUALS;
    case ExpressionType::LESS_THAN:
        return ExpressionType::GREATER_THAN;
    case ExpressionType::LESS_THAN_EQUALS:
        return ExpressionType::GREATER_THAN_EQUALS;
    default:
        return type;
    }
}

// LCOV_EXCL_START
std::string ExpressionTypeUtil::toString(ExpressionType type) {
    switch (type) {
    case ExpressionType::OR:
        return "OR";
    case ExpressionType::XOR:
        return "XOR";
    case ExpressionType::AND:
        return "AND";
    case ExpressionType::NOT:
        return "NOT";
    case ExpressionType::EQUALS:
        return EqualsFunction::name;
    case ExpressionType::NOT_EQUALS:
        return NotEqualsFunction::name;
    case ExpressionType::GREATER_THAN:
        return GreaterThanFunction::name;
    case ExpressionType::GREATER_THAN_EQUALS:
        return GreaterThanEqualsFunction::name;
    case ExpressionType::LESS_THAN:
        return LessThanFunction::name;
    case ExpressionType::LESS_THAN_EQUALS:
        return LessThanEqualsFunction::name;
    case ExpressionType::IS_NULL:
        return "IS_NULL";
    case ExpressionType::IS_NOT_NULL:
        return "IS_NOT_NULL";
    case ExpressionType::PROPERTY:
        return "PROPERTY";
    case ExpressionType::LITERAL:
        return "LITERAL";
    case ExpressionType::STAR:
        return "STAR";
    case ExpressionType::VARIABLE:
        return "VARIABLE";
    case ExpressionType::PATH:
        return "PATH";
    case ExpressionType::PATTERN:
        return "PATTERN";
    case ExpressionType::PARAMETER:
        return "PARAMETER";
    case ExpressionType::FUNCTION:
        return "SCALAR_FUNCTION";
    case ExpressionType::AGGREGATE_FUNCTION:
        return "AGGREGATE_FUNCTION";
    case ExpressionType::SUBQUERY:
        return "SUBQUERY";
    case ExpressionType::CASE_ELSE:
        return "CASE_ELSE";
    case ExpressionType::GRAPH:
        return "GRAPH";
    case ExpressionType::LAMBDA:
        return "LAMBDA";
    default:
        KU_UNREACHABLE;
    }
}

std::string ExpressionTypeUtil::toParsableString(ExpressionType type) {
    switch (type) {
    case ExpressionType::EQUALS:
        return "=";
    case ExpressionType::NOT_EQUALS:
        return "<>";
    case ExpressionType::GREATER_THAN:
        return ">";
    case ExpressionType::GREATER_THAN_EQUALS:
        return ">=";
    case ExpressionType::LESS_THAN:
        return "<";
    case ExpressionType::LESS_THAN_EQUALS:
        return "<=";
    case ExpressionType::IS_NULL:
        return "IS NULL";
    case ExpressionType::IS_NOT_NULL:
        return "IS NOT NULL";
    default:
        throw RuntimeException(stringFormat(
            "ExpressionTypeUtil::toParsableString not implemented for {}", toString(type)));
    }
}
// LCOV_EXCL_STOP

} // namespace common
} // namespace lbug
