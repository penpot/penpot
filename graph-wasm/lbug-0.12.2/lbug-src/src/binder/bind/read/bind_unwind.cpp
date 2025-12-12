#include "binder/binder.h"
#include "binder/expression/expression_util.h"
#include "binder/query/reading_clause/bound_unwind_clause.h"
#include "parser/query/reading_clause/unwind_clause.h"

using namespace lbug::parser;
using namespace lbug::common;

namespace lbug {
namespace binder {

// E.g. UNWIND $1. We cannot validate $1 has data type LIST until we see the actual parameter.
static bool skipDataTypeValidation(const Expression& expr) {
    return expr.expressionType == ExpressionType::PARAMETER &&
           expr.getDataType().getLogicalTypeID() == LogicalTypeID::ANY;
}

std::unique_ptr<BoundReadingClause> Binder::bindUnwindClause(const ReadingClause& readingClause) {
    auto& unwindClause = readingClause.constCast<UnwindClause>();
    auto boundExpression = expressionBinder.bindExpression(*unwindClause.getExpression());
    auto aliasName = unwindClause.getAlias();
    std::shared_ptr<Expression> alias;
    if (boundExpression->getDataType().getLogicalTypeID() == LogicalTypeID::ARRAY) {
        auto targetType =
            LogicalType::LIST(ArrayType::getChildType(boundExpression->dataType).copy());
        boundExpression = expressionBinder.implicitCast(boundExpression, targetType);
    }
    if (!skipDataTypeValidation(*boundExpression)) {
        if (ExpressionUtil::isNullLiteral(*boundExpression)) {
            // For the `unwind NULL` clause, we assign the STRING[] type to the NULL literal.
            alias = createVariable(aliasName, LogicalType::STRING());
            boundExpression = expressionBinder.implicitCast(boundExpression,
                LogicalType::LIST(LogicalType::STRING()));
        } else {
            ExpressionUtil::validateDataType(*boundExpression, LogicalTypeID::LIST);
            boundExpression = expressionBinder.implicitCastIfNecessary(boundExpression,
                LogicalTypeUtils::purgeAny(boundExpression->dataType, LogicalType::STRING()));
            alias = createVariable(aliasName, ListType::getChildType(boundExpression->dataType));
        }
    } else {
        alias = createVariable(aliasName, LogicalType::ANY());
    }
    std::shared_ptr<Expression> idExpr = nullptr;
    if (scope.hasMemorizedTableIDs(boundExpression->getAlias())) {
        auto entries = scope.getMemorizedTableEntries(boundExpression->getAlias());
        auto node = createQueryNode(aliasName, entries);
        idExpr = node->getInternalID();
        scope.addNodeReplacement(node);
    }
    return make_unique<BoundUnwindClause>(std::move(boundExpression), std::move(alias),
        std::move(idExpr));
}

} // namespace binder
} // namespace lbug
