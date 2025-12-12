#pragma once

#include "planner/operator/logical_plan.h"
#include "planner/subplans_table.h"

namespace lbug {
namespace planner {

class JoinOrderEnumeratorContext {
    friend class Planner;

public:
    JoinOrderEnumeratorContext()
        : currentLevel{0}, maxLevel{0}, subPlansTable{std::make_unique<SubPlansTable>()},
          queryGraph{nullptr} {}
    DELETE_COPY_DEFAULT_MOVE(JoinOrderEnumeratorContext);

    void init(const binder::QueryGraph* queryGraph, const binder::expression_vector& predicates);

    binder::expression_vector getWhereExpressions() { return whereExpressionsSplitOnAND; }

    bool containPlans(const binder::SubqueryGraph& subqueryGraph) const {
        return subPlansTable->containSubgraphPlans(subqueryGraph);
    }
    const std::vector<LogicalPlan>& getPlans(const binder::SubqueryGraph& subqueryGraph) const {
        return subPlansTable->getSubgraphPlans(subqueryGraph);
    }
    void addPlan(const binder::SubqueryGraph& subqueryGraph, LogicalPlan plan) {
        subPlansTable->addPlan(subqueryGraph, std::move(plan));
    }

    binder::SubqueryGraph getEmptySubqueryGraph() const {
        return binder::SubqueryGraph(*queryGraph);
    }
    binder::SubqueryGraph getFullyMatchedSubqueryGraph() const;

    const binder::QueryGraph* getQueryGraph() { return queryGraph; }

    void resetState();

private:
    binder::expression_vector whereExpressionsSplitOnAND;

    uint32_t currentLevel;
    uint32_t maxLevel;

    std::unique_ptr<SubPlansTable> subPlansTable;
    const binder::QueryGraph* queryGraph;
};

} // namespace planner
} // namespace lbug
