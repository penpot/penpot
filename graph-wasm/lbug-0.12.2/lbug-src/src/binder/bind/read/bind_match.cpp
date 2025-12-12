#include "binder/binder.h"
#include "binder/query/reading_clause/bound_match_clause.h"
#include "common/exception/binder.h"
#include "parser/query/reading_clause/match_clause.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

static void collectHintPattern(const BoundJoinHintNode& node, binder::expression_set& set) {
    if (node.isLeaf()) {
        set.insert(node.nodeOrRel);
        return;
    }
    for (auto& child : node.children) {
        collectHintPattern(*child, set);
    }
}

static void validateHintCompleteness(const BoundJoinHintNode& root, const QueryGraph& queryGraph) {
    binder::expression_set set;
    collectHintPattern(root, set);
    for (auto& nodeOrRel : queryGraph.getAllPatterns()) {
        if (nodeOrRel->getVariableName().empty()) {
            throw BinderException(
                "Cannot hint join order in a match patter with anonymous node or relationship.");
        }
        if (!set.contains(nodeOrRel)) {
            throw BinderException(
                stringFormat("Cannot find {} in join hint.", nodeOrRel->toString()));
        }
    }
}

std::unique_ptr<BoundReadingClause> Binder::bindMatchClause(const ReadingClause& readingClause) {
    auto& matchClause = readingClause.constCast<MatchClause>();
    auto boundGraphPattern = bindGraphPattern(matchClause.getPatternElementsRef());
    if (matchClause.hasWherePredicate()) {
        boundGraphPattern.where = bindWhereExpression(*matchClause.getWherePredicate());
    }
    rewriteMatchPattern(boundGraphPattern);
    auto boundMatch = std::make_unique<BoundMatchClause>(
        std::move(boundGraphPattern.queryGraphCollection), matchClause.getMatchClauseType());
    if (matchClause.hasHint()) {
        boundMatch->setHint(
            bindJoinHint(*boundMatch->getQueryGraphCollection(), *matchClause.getHint()));
    }
    boundMatch->setPredicate(boundGraphPattern.where);
    return boundMatch;
}

std::shared_ptr<BoundJoinHintNode> Binder::bindJoinHint(
    const QueryGraphCollection& queryGraphCollection, const JoinHintNode& joinHintNode) {
    if (queryGraphCollection.getNumQueryGraphs() > 1) {
        throw BinderException("Join hint on disconnected match pattern is not supported.");
    }
    auto hint = bindJoinNode(joinHintNode);
    validateHintCompleteness(*hint, *queryGraphCollection.getQueryGraph(0));
    return hint;
}

std::shared_ptr<BoundJoinHintNode> Binder::bindJoinNode(const JoinHintNode& joinHintNode) {
    if (joinHintNode.isLeaf()) {
        std::shared_ptr<Expression> pattern = nullptr;
        if (scope.contains(joinHintNode.variableName)) {
            pattern = scope.getExpression(joinHintNode.variableName);
        }
        if (pattern == nullptr || pattern->expressionType != ExpressionType::PATTERN) {
            throw BinderException(stringFormat("Cannot bind {} to a node or relationship pattern",
                joinHintNode.variableName));
        }
        return std::make_shared<BoundJoinHintNode>(std::move(pattern));
    }
    auto node = std::make_shared<BoundJoinHintNode>();
    for (auto& child : joinHintNode.children) {
        node->addChild(bindJoinNode(*child));
    }
    return node;
}

void Binder::rewriteMatchPattern(BoundGraphPattern& boundGraphPattern) {
    // Rewrite self loop edge
    // e.g. rewrite (a)-[e]->(a) as [a]-[e]->(b) WHERE id(a) = id(b)
    expression_vector selfLoopEdgePredicates;
    auto& graphCollection = boundGraphPattern.queryGraphCollection;
    for (auto i = 0u; i < graphCollection.getNumQueryGraphs(); ++i) {
        auto queryGraph = graphCollection.getQueryGraphUnsafe(i);
        for (auto& queryRel : queryGraph->getQueryRels()) {
            if (!queryRel->isSelfLoop()) {
                continue;
            }
            auto src = queryRel->getSrcNode();
            auto dst = queryRel->getDstNode();
            auto newDst = createQueryNode(dst->getVariableName(), dst->getEntries());
            queryGraph->addQueryNode(newDst);
            queryRel->setDstNode(newDst);
            auto predicate = expressionBinder.createEqualityComparisonExpression(
                src->getInternalID(), newDst->getInternalID());
            selfLoopEdgePredicates.push_back(std::move(predicate));
        }
    }
    auto where = boundGraphPattern.where;
    for (auto& predicate : selfLoopEdgePredicates) {
        where = expressionBinder.combineBooleanExpressions(ExpressionType::AND, predicate, where);
    }
    // Rewrite key value pairs in MATCH clause as predicate
    for (auto i = 0u; i < graphCollection.getNumQueryGraphs(); ++i) {
        auto queryGraph = graphCollection.getQueryGraphUnsafe(i);
        for (auto& pattern : queryGraph->getAllPatterns()) {
            for (auto& [propertyName, rhs] : pattern->getPropertyDataExprRef()) {
                auto propertyExpr =
                    expressionBinder.bindNodeOrRelPropertyExpression(*pattern, propertyName);
                auto predicate =
                    expressionBinder.createEqualityComparisonExpression(propertyExpr, rhs);
                where = expressionBinder.combineBooleanExpressions(ExpressionType::AND, predicate,
                    where);
            }
        }
    }
    boundGraphPattern.where = std::move(where);
}

} // namespace binder
} // namespace lbug
