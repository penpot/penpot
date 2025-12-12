#include "binder/binder.h"
#include "binder/expression/expression_util.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "catalog/catalog.h"
#include "common/exception/binder.h"
#include "function/built_in_function_utils.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::parser;
using namespace lbug::function;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindComparisonExpression(
    const ParsedExpression& parsedExpression) {
    expression_vector children;
    for (auto i = 0u; i < parsedExpression.getNumChildren(); ++i) {
        auto child = bindExpression(*parsedExpression.getChild(i));
        children.push_back(std::move(child));
    }
    return bindComparisonExpression(parsedExpression.getExpressionType(), children);
}

static bool isNodeOrRel(const Expression& expression) {
    switch (expression.getDataType().getLogicalTypeID()) {
    case LogicalTypeID::NODE:
    case LogicalTypeID::REL:
        return true;
    default:
        return false;
    }
}

std::shared_ptr<Expression> ExpressionBinder::bindComparisonExpression(
    ExpressionType expressionType, const expression_vector& children) {
    // Rewrite node or rel comparison
    KU_ASSERT(children.size() == 2);
    if (isNodeOrRel(*children[0]) && isNodeOrRel(*children[1])) {
        expression_vector newChildren;
        newChildren.push_back(children[0]->constCast<NodeOrRelExpression>().getInternalID());
        newChildren.push_back(children[1]->constCast<NodeOrRelExpression>().getInternalID());
        return bindComparisonExpression(expressionType, newChildren);
    }

    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto functionName = ExpressionTypeUtil::toString(expressionType);
    LogicalType combinedType(LogicalTypeID::ANY);
    if (!ExpressionUtil::tryCombineDataType(children, combinedType)) {
        throw BinderException(stringFormat("Type Mismatch: Cannot compare types {} and {}",
            children[0]->dataType.toString(), children[1]->dataType.toString()));
    }
    if (combinedType.getLogicalTypeID() == LogicalTypeID::ANY) {
        combinedType = LogicalType(LogicalTypeID::INT8);
    }
    std::vector<LogicalType> childrenTypes;
    for (auto i = 0u; i < children.size(); i++) {
        childrenTypes.push_back(combinedType.copy());
    }
    auto entry =
        catalog->getFunctionEntry(transaction, functionName)->ptrCast<FunctionCatalogEntry>();
    auto function = BuiltInFunctionsUtils::matchFunction(functionName, childrenTypes, entry)
                        ->ptrCast<ScalarFunction>();
    expression_vector childrenAfterCast;
    for (auto i = 0u; i < children.size(); ++i) {
        if (children[i]->dataType != combinedType) {
            childrenAfterCast.push_back(forceCast(children[i], combinedType));
        } else {
            childrenAfterCast.push_back(children[i]);
        }
    }
    if (function->bindFunc) {
        // Resolve exec and select function if necessary
        // Only used for decimal at the moment. See `bindDecimalCompare`.
        function->bindFunc({childrenAfterCast, function, nullptr,
            std::vector<std::string>{} /* optionalParams */});
    }
    auto bindData = std::make_unique<FunctionBindData>(LogicalType(function->returnTypeID));
    auto uniqueExpressionName =
        ScalarFunctionExpression::getUniqueName(function->name, childrenAfterCast);
    return std::make_shared<ScalarFunctionExpression>(expressionType, function->copy(),
        std::move(bindData), std::move(childrenAfterCast), uniqueExpressionName);
}

std::shared_ptr<Expression> ExpressionBinder::createEqualityComparisonExpression(
    std::shared_ptr<Expression> left, std::shared_ptr<Expression> right) {
    return bindComparisonExpression(ExpressionType::EQUALS,
        expression_vector{std::move(left), std::move(right)});
}

} // namespace binder
} // namespace lbug
