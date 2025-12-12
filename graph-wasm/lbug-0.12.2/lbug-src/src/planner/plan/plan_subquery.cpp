#include "binder/expression/expression_util.h"
#include "binder/expression/subquery_expression.h"
#include "binder/expression_visitor.h"
#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

static expression_vector getDependentExprs(std::shared_ptr<Expression> expr, const Schema& schema) {
    auto analyzer = GroupDependencyAnalyzer(true /* collectDependentExpr */, schema);
    analyzer.visit(expr);
    return analyzer.getDependentExprs();
}

expression_vector Planner::getCorrelatedExprs(const QueryGraphCollection& collection,
    const expression_vector& predicates, Schema* outerSchema) {
    expression_vector result;
    for (auto& predicate : predicates) {
        for (auto& expression : getDependentExprs(predicate, *outerSchema)) {
            result.push_back(expression);
        }
    }
    for (auto& node : collection.getQueryNodes()) {
        if (outerSchema->isExpressionInScope(*node->getInternalID())) {
            result.push_back(node->getInternalID());
        }
    }
    return ExpressionUtil::removeDuplication(result);
}

class SubqueryPredicatePullUpAnalyzer {
public:
    SubqueryPredicatePullUpAnalyzer(const Schema& schema,
        const QueryGraphCollection& queryGraphCollection)
        : schema{schema}, queryGraphCollection{queryGraphCollection} {}

    bool analyze(const expression_vector& predicates) {
        expression_vector correlatedPredicates;
        for (auto& predicate : predicates) {
            if (getDependentExprs(predicate, schema).empty()) {
                nonCorrelatedPredicates.push_back(predicate);
            } else {
                correlatedPredicates.push_back(predicate);
            }
        }
        for (auto predicate : correlatedPredicates) {
            auto [left, right] = analyze(predicate);
            if (left == nullptr) {
                return false;
            }
            joinConditions.emplace_back(left, right);
        }
        for (auto& node : queryGraphCollection.getQueryNodes()) {
            if (schema.isExpressionInScope(*node->getInternalID())) {
                joinConditions.emplace_back(node->getInternalID(), node->getInternalID());
            }
        }
        return true;
    }

    expression_vector getNonCorrelatedPredicates() const { return nonCorrelatedPredicates; }
    std::vector<binder::expression_pair> getJoinConditions() const { return joinConditions; }

    expression_vector getCorrelatedInternalIDs() const {
        expression_vector exprs;
        for (auto& node : queryGraphCollection.getQueryNodes()) {
            if (schema.isExpressionInScope(*node->getInternalID())) {
                exprs.push_back(node->getInternalID());
            }
        }
        return exprs;
    }

private:
    expression_pair analyze(std::shared_ptr<Expression> predicate) {
        if (predicate->expressionType != common::ExpressionType::EQUALS) {
            return {nullptr, nullptr};
        }
        auto left = predicate->getChild(0);
        auto right = predicate->getChild(1);
        if (isUnnestableJoinCondition(*left, *right)) {
            return {left, right};
        }
        if (isUnnestableJoinCondition(*right, *left)) {
            return {right, left};
        }
        return {nullptr, nullptr};
    }

    bool isUnnestableJoinCondition(const Expression& left, const Expression& right) {
        return right.expressionType == ExpressionType::PROPERTY &&
               schema.isExpressionInScope(left) && !schema.isExpressionInScope(right);
    }

private:
    const Schema& schema;
    const QueryGraphCollection& queryGraphCollection;

    expression_vector nonCorrelatedPredicates;
    std::vector<binder::expression_pair> joinConditions;
};

void Planner::planOptionalMatch(const QueryGraphCollection& queryGraphCollection,
    const expression_vector& predicates, LogicalPlan& leftPlan,
    std::shared_ptr<BoundJoinHintNode> hint) {
    planOptionalMatch(queryGraphCollection, predicates, nullptr /* mark */, leftPlan,
        std::move(hint));
}

void Planner::planOptionalMatch(const QueryGraphCollection& queryGraphCollection,
    const expression_vector& predicates, std::shared_ptr<Expression> mark, LogicalPlan& leftPlan,
    std::shared_ptr<BoundJoinHintNode> hint) {
    expression_vector correlatedExprs;
    if (!leftPlan.isEmpty()) {
        correlatedExprs =
            getCorrelatedExprs(queryGraphCollection, predicates, leftPlan.getSchema());
    }
    auto info = QueryGraphPlanningInfo();
    info.hint = hint;
    if (leftPlan.isEmpty()) {
        // Optional match is the first clause, e.g. OPTIONAL MATCH <pattern> RETURN *
        info.predicates = predicates;
        auto plan = planQueryGraphCollection(queryGraphCollection, info);
        leftPlan.setLastOperator(plan.getLastOperator());
        appendOptionalAccumulate(mark, leftPlan);
        return;
    }
    if (correlatedExprs.empty()) {
        // Plan uncorrelated subquery (think of this as a CTE)
        info.predicates = predicates;
        auto rightPlan = planQueryGraphCollection(queryGraphCollection, info);
        if (leftPlan.hasUpdate()) {
            appendAccOptionalCrossProduct(mark, leftPlan, rightPlan, leftPlan);
        } else {
            appendOptionalCrossProduct(mark, leftPlan, rightPlan, leftPlan);
        }
        return;
    }
    // Plan correlated subquery
    info.corrExprsCard = leftPlan.getCardinality();
    auto analyzer = SubqueryPredicatePullUpAnalyzer(*leftPlan.getSchema(), queryGraphCollection);
    std::vector<expression_pair> joinConditions;
    LogicalPlan rightPlan;
    if (analyzer.analyze(predicates)) {
        // Unnest as left join
        info.subqueryType = SubqueryPlanningType::UNNEST_CORRELATED;
        info.corrExprs = analyzer.getCorrelatedInternalIDs();
        info.predicates = analyzer.getNonCorrelatedPredicates();
        rightPlan = planQueryGraphCollectionInNewContext(queryGraphCollection, info);
        joinConditions = analyzer.getJoinConditions();
    } else {
        // Unnest as expression scan + distinct & inner join
        info.subqueryType = SubqueryPlanningType::CORRELATED;
        info.corrExprs = correlatedExprs;
        info.predicates = predicates;
        for (auto& expr : correlatedExprs) {
            joinConditions.emplace_back(expr, expr);
        }
        rightPlan = planQueryGraphCollectionInNewContext(queryGraphCollection, info);
        appendAccumulate(correlatedExprs, leftPlan);
    }
    if (leftPlan.hasUpdate()) {
        appendAccHashJoin(joinConditions, JoinType::LEFT, mark, leftPlan, rightPlan, leftPlan);
    } else {
        appendHashJoin(joinConditions, JoinType::LEFT, mark, leftPlan, rightPlan, leftPlan);
    }
}

void Planner::planRegularMatch(const QueryGraphCollection& queryGraphCollection,
    const expression_vector& predicates, LogicalPlan& leftPlan,
    std::shared_ptr<BoundJoinHintNode> hint) {
    expression_vector predicatesToPushDown, predicatesToPullUp;
    // E.g. MATCH (a) WITH COUNT(*) AS s MATCH (b) WHERE b.age > s
    // "b.age > s" should be pulled up after both MATCH clauses are joined.
    for (auto& predicate : predicates) {
        if (getDependentExprs(predicate, *leftPlan.getSchema()).empty()) {
            predicatesToPushDown.push_back(predicate);
        } else {
            predicatesToPullUp.push_back(predicate);
        }
    }
    auto correlatedExprs =
        getCorrelatedExprs(queryGraphCollection, predicatesToPushDown, leftPlan.getSchema());
    auto joinNodeIDs =
        ExpressionUtil::getExpressionsWithDataType(correlatedExprs, LogicalTypeID::INTERNAL_ID);
    auto info = QueryGraphPlanningInfo();
    info.predicates = predicatesToPushDown;
    info.hint = hint;
    if (joinNodeIDs.empty()) {
        info.subqueryType = SubqueryPlanningType::NONE;
        auto rightPlan = planQueryGraphCollectionInNewContext(queryGraphCollection, info);
        if (leftPlan.hasUpdate()) {
            appendCrossProduct(rightPlan, leftPlan, leftPlan);
        } else {
            appendCrossProduct(leftPlan, rightPlan, leftPlan);
        }
    } else {
        // TODO(Xiyang): there is a question regarding if we want to plan as a correlated subquery
        // Multi-part query is actually CTE and CTE can be considered as a subquery but does not
        // scan from outer.
        info.subqueryType = SubqueryPlanningType::UNNEST_CORRELATED;
        info.corrExprs = joinNodeIDs;
        info.corrExprsCard = leftPlan.getCardinality();
        auto rightPlan = planQueryGraphCollectionInNewContext(queryGraphCollection, info);
        if (leftPlan.hasUpdate()) {
            appendHashJoin(joinNodeIDs, JoinType::INNER, rightPlan, leftPlan, leftPlan);
        } else {
            appendHashJoin(joinNodeIDs, JoinType::INNER, leftPlan, rightPlan, leftPlan);
        }
    }
    for (auto& predicate : predicatesToPullUp) {
        appendFilter(predicate, leftPlan);
    }
}

void Planner::planSubquery(const std::shared_ptr<Expression>& expression, LogicalPlan& outerPlan) {
    KU_ASSERT(expression->expressionType == ExpressionType::SUBQUERY);
    auto subquery = expression->ptrCast<SubqueryExpression>();
    auto correlatedExprs = getDependentExprs(expression, *outerPlan.getSchema());
    auto predicates = subquery->getPredicatesSplitOnAnd();
    LogicalPlan innerPlan;
    auto info = QueryGraphPlanningInfo();
    info.hint = subquery->getHint();
    if (correlatedExprs.empty()) {
        // Plan uncorrelated subquery
        info.subqueryType = SubqueryPlanningType::NONE;
        info.predicates = predicates;
        innerPlan =
            planQueryGraphCollectionInNewContext(*subquery->getQueryGraphCollection(), info);
        expression_vector emptyHashKeys;
        auto projectExprs = expression_vector{subquery->getProjectionExpr()};
        switch (subquery->getSubqueryType()) {
        case common::SubqueryType::EXISTS: {
            auto aggregates = expression_vector{subquery->getCountStarExpr()};
            appendAggregate(emptyHashKeys, aggregates, innerPlan);
            appendProjection(projectExprs, innerPlan);
        } break;
        case common::SubqueryType::COUNT: {
            appendAggregate(emptyHashKeys, projectExprs, innerPlan);
        } break;
        default:
            KU_UNREACHABLE;
        }
        appendCrossProduct(outerPlan, innerPlan, outerPlan);
        return;
    }
    // Plan correlated subquery
    info.corrExprsCard = outerPlan.getCardinality();
    auto analyzer = SubqueryPredicatePullUpAnalyzer(*outerPlan.getSchema(),
        *subquery->getQueryGraphCollection());
    std::vector<expression_pair> joinConditions;
    if (analyzer.analyze(predicates)) {
        // Unnest as inner join
        info.subqueryType = SubqueryPlanningType::UNNEST_CORRELATED;
        info.corrExprs = analyzer.getCorrelatedInternalIDs();
        info.predicates = analyzer.getNonCorrelatedPredicates();
        innerPlan =
            planQueryGraphCollectionInNewContext(*subquery->getQueryGraphCollection(), info);
        joinConditions = analyzer.getJoinConditions();
    } else {
        // Unnest as expression scan + distinct & inner join
        info.subqueryType = SubqueryPlanningType::CORRELATED;
        info.corrExprs = correlatedExprs;
        info.predicates = predicates;
        for (auto& expr : correlatedExprs) {
            joinConditions.emplace_back(expr, expr);
        }
        innerPlan =
            planQueryGraphCollectionInNewContext(*subquery->getQueryGraphCollection(), info);
        appendAccumulate(correlatedExprs, outerPlan);
    }
    switch (subquery->getSubqueryType()) {
    case common::SubqueryType::EXISTS: {
        appendMarkJoin(joinConditions, expression, outerPlan, innerPlan, outerPlan);
    } break;
    case common::SubqueryType::COUNT: {
        expression_vector hashKeys;
        for (auto& joinCondition : joinConditions) {
            hashKeys.push_back(joinCondition.second);
        }
        appendAggregate(hashKeys, expression_vector{subquery->getProjectionExpr()}, innerPlan);
        appendHashJoin(joinConditions, common::JoinType::COUNT, nullptr, outerPlan, innerPlan,
            outerPlan);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void Planner::planSubqueryIfNecessary(std::shared_ptr<Expression> expression, LogicalPlan& plan) {
    auto collector = SubqueryExprCollector();
    collector.visit(expression);
    if (collector.hasSubquery()) {
        for (auto& expr : collector.getSubqueryExprs()) {
            if (plan.getSchema()->isExpressionInScope(*expr)) {
                continue;
            }
            planSubquery(expr, plan);
        }
    }
}

} // namespace planner
} // namespace lbug
