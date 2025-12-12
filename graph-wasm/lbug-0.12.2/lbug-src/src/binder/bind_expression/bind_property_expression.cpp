#include "binder/binder.h"
#include "binder/expression/expression_util.h"
#include "binder/expression/node_rel_expression.h"
#include "binder/expression_binder.h"
#include "common/cast.h"
#include "common/exception/binder.h"
#include "common/string_format.h"
#include "function/struct/vector_struct_functions.h"
#include "parser/expression/parsed_property_expression.h"

using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

static bool isNodeOrRelPattern(const Expression& expression) {
    return ExpressionUtil::isNodePattern(expression) || ExpressionUtil::isRelPattern(expression);
}

static bool isStructPattern(const Expression& expression) {
    auto logicalTypeID = expression.getDataType().getLogicalTypeID();
    return logicalTypeID == LogicalTypeID::NODE || logicalTypeID == LogicalTypeID::REL ||
           logicalTypeID == LogicalTypeID::STRUCT;
}

expression_vector ExpressionBinder::bindPropertyStarExpression(
    const parser::ParsedExpression& parsedExpression) {
    auto child = bindExpression(*parsedExpression.getChild(0));
    if (isNodeOrRelPattern(*child)) {
        return bindNodeOrRelPropertyStarExpression(*child);
    } else if (isStructPattern(*child)) {
        return bindStructPropertyStarExpression(child);
    } else {
        throw BinderException(stringFormat("Cannot bind property for expression {} with type {}.",
            child->toString(), ExpressionTypeUtil::toString(child->expressionType)));
    }
}

expression_vector ExpressionBinder::bindNodeOrRelPropertyStarExpression(const Expression& child) {
    expression_vector result;
    auto& nodeOrRel = child.constCast<NodeOrRelExpression>();
    for (auto& property : nodeOrRel.getPropertyExpressions()) {
        if (Binder::reservedInPropertyLookup(property->getPropertyName())) {
            continue;
        }
        result.push_back(property);
    }
    return result;
}

expression_vector ExpressionBinder::bindStructPropertyStarExpression(
    const std::shared_ptr<Expression>& child) {
    expression_vector result;
    const auto& childType = child->getDataType();
    for (auto& field : StructType::getFields(childType)) {
        result.push_back(bindStructPropertyExpression(child, field.getName()));
    }
    return result;
}

std::shared_ptr<Expression> ExpressionBinder::bindPropertyExpression(
    const ParsedExpression& parsedExpression) {
    auto& propertyExpression = parsedExpression.constCast<ParsedPropertyExpression>();
    if (propertyExpression.isStar()) {
        throw BinderException(stringFormat("Cannot bind {} as a single property expression.",
            parsedExpression.toString()));
    }
    auto propertyName = propertyExpression.getPropertyName();
    auto child = bindExpression(*parsedExpression.getChild(0));
    ExpressionUtil::validateDataType(*child,
        std::vector<LogicalTypeID>{LogicalTypeID::NODE, LogicalTypeID::REL, LogicalTypeID::STRUCT,
            LogicalTypeID::ANY});
    if (config.bindOrderByAfterAggregate) {
        // See the declaration of this field for more information.
        return bindStructPropertyExpression(child, propertyName);
    }
    if (isNodeOrRelPattern(*child)) {
        if (Binder::reservedInPropertyLookup(propertyName)) {
            // Note we don't expose direct access to internal properties in case user tries to
            // modify them. However, we can expose indirect read-only access through function e.g.
            // ID().
            throw BinderException(
                propertyName + " is reserved for system usage. External access is not allowed.");
        }
        return bindNodeOrRelPropertyExpression(*child, propertyName);
    } else if (isStructPattern(*child)) {
        return bindStructPropertyExpression(child, propertyName);
    } else if (child->getDataType().getLogicalTypeID() == LogicalTypeID::ANY) {
        return createVariableExpression(LogicalType::ANY(), binder->getUniqueExpressionName(""));
    } else {
        throw BinderException(stringFormat("Cannot bind property for expression {} with type {}.",
            child->toString(), ExpressionTypeUtil::toString(child->expressionType)));
    }
}

std::shared_ptr<Expression> ExpressionBinder::bindNodeOrRelPropertyExpression(
    const Expression& child, const std::string& propertyName) {
    auto& nodeOrRel = child.constCast<NodeOrRelExpression>();
    // TODO(Xiyang): we should be able to remove l97-l100 after removing propertyDataExprs from node
    // & rel expression.
    if (propertyName == InternalKeyword::ID &&
        child.dataType.getLogicalTypeID() == common::LogicalTypeID::NODE) {
        auto& node = ku_dynamic_cast<const NodeExpression&>(child);
        return node.getInternalID();
    }
    if (!nodeOrRel.hasPropertyExpression(propertyName)) {
        throw BinderException(
            "Cannot find property " + propertyName + " for " + child.toString() + ".");
    }
    // We always create new object when binding expression except when referring to an existing
    // alias when binding variables.
    return nodeOrRel.getPropertyExpression(propertyName)->copy();
}

std::shared_ptr<Expression> ExpressionBinder::bindStructPropertyExpression(
    std::shared_ptr<Expression> child, const std::string& propertyName) {
    auto children = expression_vector{std::move(child), createLiteralExpression(propertyName)};
    return bindScalarFunctionExpression(children, function::StructExtractFunctions::name);
}

} // namespace binder
} // namespace lbug
