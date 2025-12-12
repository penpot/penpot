#include "storage/predicate/column_predicate.h"

#include "binder/expression/literal_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "storage/predicate/constant_predicate.h"
#include "storage/predicate/null_predicate.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace storage {

ZoneMapCheckResult ColumnPredicateSet::checkZoneMap(const MergedColumnChunkStats& stats) const {
    for (auto& predicate : predicates) {
        if (predicate->checkZoneMap(stats) == ZoneMapCheckResult::SKIP_SCAN) {
            return ZoneMapCheckResult::SKIP_SCAN;
        }
    }
    return ZoneMapCheckResult::ALWAYS_SCAN;
}

std::string ColumnPredicateSet::toString() const {
    if (predicates.empty()) {
        return {};
    }
    auto result = predicates[0]->toString();
    for (auto i = 1u; i < predicates.size(); ++i) {
        result += stringFormat(" AND {}", predicates[i]->toString());
    }
    return result;
}

static bool isColumnRef(ExpressionType type) {
    return type == ExpressionType::PROPERTY || type == ExpressionType::VARIABLE;
}

static bool isCastedColumnRef(const Expression& expr) {
    if (expr.expressionType == ExpressionType::FUNCTION) {
        const auto& funcExpr = expr.constCast<ScalarFunctionExpression>();
        if (funcExpr.getFunction().name.starts_with("CAST")) {
            KU_ASSERT(funcExpr.getNumChildren() > 0);
            return isColumnRef(funcExpr.getChild(0)->expressionType);
        }
    }
    return false;
}

static bool isColumnOrCastedColumnRef(const Expression& expr) {
    return isColumnRef(expr.expressionType) || isCastedColumnRef(expr);
}

static bool isColumnRefConstantPair(const Expression& left, const Expression& right) {
    return isColumnOrCastedColumnRef(left) && right.expressionType == ExpressionType::LITERAL;
}

static bool columnMatchesExprChild(const Expression& column, const Expression& expr) {
    return (expr.getNumChildren() > 0 && column == *expr.getChild(0));
}

static std::unique_ptr<ColumnPredicate> tryConvertToConstColumnPredicate(const Expression& column,
    const Expression& predicate) {
    if (isColumnRefConstantPair(*predicate.getChild(0), *predicate.getChild(1))) {
        if (column != *predicate.getChild(0) &&
            !columnMatchesExprChild(column, *predicate.getChild(0))) {
            return nullptr;
        }
        auto value = predicate.getChild(1)->constCast<LiteralExpression>().getValue();
        return std::make_unique<ColumnConstantPredicate>(column.toString(),
            predicate.expressionType, value);
    } else if (isColumnRefConstantPair(*predicate.getChild(1), *predicate.getChild(0))) {
        if (column != *predicate.getChild(1) &&
            !columnMatchesExprChild(column, *predicate.getChild(1))) {
            return nullptr;
        }
        auto value = predicate.getChild(0)->constCast<LiteralExpression>().getValue();
        auto expressionType =
            ExpressionTypeUtil::reverseComparisonDirection(predicate.expressionType);
        return std::make_unique<ColumnConstantPredicate>(column.toString(), expressionType, value);
    }
    // Not a predicate that runs on this property.
    return nullptr;
}

static std::unique_ptr<ColumnPredicate> tryConvertToIsNull(const Expression& column,
    const Expression& predicate) {
    // we only convert simple predicates
    if (isColumnOrCastedColumnRef(*predicate.getChild(0)) && column == *predicate.getChild(0)) {
        return std::make_unique<ColumnNullPredicate>(column.toString(), ExpressionType::IS_NULL);
    }
    return nullptr;
}

static std::unique_ptr<ColumnPredicate> tryConvertToIsNotNull(const Expression& column,
    const Expression& predicate) {
    if (isColumnOrCastedColumnRef(*predicate.getChild(0)) && column == *predicate.getChild(0)) {
        return std::make_unique<ColumnNullPredicate>(column.toString(),
            ExpressionType::IS_NOT_NULL);
    }
    return nullptr;
}

std::unique_ptr<ColumnPredicate> ColumnPredicateUtil::tryConvert(const Expression& property,
    const Expression& predicate) {
    if (ExpressionTypeUtil::isComparison(predicate.expressionType)) {
        return tryConvertToConstColumnPredicate(property, predicate);
    }
    switch (predicate.expressionType) {
    case common::ExpressionType::IS_NULL:
        return tryConvertToIsNull(property, predicate);
    case common::ExpressionType::IS_NOT_NULL:
        return tryConvertToIsNotNull(property, predicate);
    default:
        return nullptr;
    }
}

std::string ColumnPredicate::toString() {
    return stringFormat("{} {}", columnName, ExpressionTypeUtil::toParsableString(expressionType));
}

} // namespace storage
} // namespace lbug
