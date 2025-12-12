#include "binder/rewriter/with_clause_projection_rewriter.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/property_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression_visitor.h"
#include "binder/visitor/property_collector.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

static void rewrite(std::shared_ptr<Expression> expr, expression_vector& projectionList,
    const std::unordered_map<std::string, expression_vector>& varNameToProperties) {
    std::string varName;
    if (ExpressionUtil::isNodePattern(*expr)) {
        auto& node = expr->constCast<NodeExpression>();
        projectionList.push_back(node.getInternalID());
        varName = node.getUniqueName();
    } else if (ExpressionUtil::isRelPattern(*expr)) {
        auto& rel = expr->constCast<RelExpression>();
        projectionList.push_back(rel.getSrcNode()->getInternalID());
        projectionList.push_back(rel.getDstNode()->getInternalID());
        projectionList.push_back(rel.getInternalID());
        if (rel.hasDirectionExpr()) {
            projectionList.push_back(rel.getDirectionExpr());
        }
        varName = rel.getUniqueName();
    } else if (ExpressionUtil::isRecursiveRelPattern(*expr)) {
        auto& rel = expr->constCast<RelExpression>();
        projectionList.push_back(rel.getLengthExpression());
        projectionList.push_back(expr);
        varName = rel.getUniqueName();
    }
    if (!varName.empty()) {
        if (varNameToProperties.contains(varName)) {
            for (auto& property : varNameToProperties.at(varName)) {
                projectionList.push_back(property);
            }
        }
    } else {
        projectionList.push_back(expr);
    }
}

static expression_vector rewrite(const expression_vector& exprs,
    const std::unordered_map<std::string, expression_vector>& varNameToProperties) {
    expression_vector projectionList;
    for (auto& expr : exprs) {
        rewrite(expr, projectionList, varNameToProperties);
    }
    return projectionList;
}

void WithClauseProjectionRewriter::visitSingleQueryUnsafe(NormalizedSingleQuery& singleQuery) {
    auto propertyCollector = PropertyCollector();
    propertyCollector.visitSingleQuerySkipNodeRel(singleQuery);
    std::unordered_map<std::string, expression_vector> varNameToProperties;
    for (auto& expr : propertyCollector.getProperties()) {
        auto& property = expr->constCast<PropertyExpression>();
        if (!varNameToProperties.contains(property.getVariableName())) {
            varNameToProperties.insert({property.getVariableName(), expression_vector{}});
        }
        varNameToProperties.at(property.getVariableName()).push_back(expr);
    }
    // Rewrite WITH clause node, relationship pattern projection as node.* & rel.*
    // Because we want to delay the evaluation of node and rel as a struct.
    for (auto i = 0u; i < singleQuery.getNumQueryParts() - 1; ++i) {
        auto queryPart = singleQuery.getQueryPartUnsafe(i);
        auto projectionBody = queryPart->getProjectionBodyUnsafe();
        auto newProjectionExprs =
            rewrite(projectionBody->getProjectionExpressions(), varNameToProperties);
        projectionBody->setProjectionExpressions(std::move(newProjectionExprs));
        auto newGroupByExprs =
            rewrite(projectionBody->getGroupByExpressions(), varNameToProperties);
        projectionBody->setGroupByExpressions(std::move(newGroupByExprs));
    }
    // Remove constant expressions from WITH clause projection list.
    for (auto i = 0u; i < singleQuery.getNumQueryParts() - 1; ++i) {
        auto queryPart = singleQuery.getQueryPartUnsafe(i);
        auto projectionBody = queryPart->getProjectionBodyUnsafe();
        // Avoid rewrite in the case of ORDER BY 1 or aggregate by constant. Because operator
        // implementation replies on expressions to be projected first.
        if (projectionBody->hasOrderByExpressions() || projectionBody->hasAggregateExpressions()) {
            continue;
        }
        expression_vector nonConstantProjectionExprs;
        for (auto& expr : projectionBody->getProjectionExpressions()) {
            if (ConstantExpressionVisitor::isConstant(*expr)) {
                continue;
            }
            nonConstantProjectionExprs.push_back(expr);
        }
        projectionBody->setProjectionExpressions(nonConstantProjectionExprs);
    }
}

} // namespace binder
} // namespace lbug
