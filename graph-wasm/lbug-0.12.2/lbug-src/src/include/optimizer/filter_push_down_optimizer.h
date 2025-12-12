#pragma once

#include "planner/operator/logical_plan.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace optimizer {

struct PredicateSet {
    binder::expression_vector equalityPredicates;
    binder::expression_vector nonEqualityPredicates;

    PredicateSet() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(PredicateSet);

    bool isEmpty() const { return equalityPredicates.empty() && nonEqualityPredicates.empty(); }
    void clear() {
        equalityPredicates.clear();
        nonEqualityPredicates.clear();
    }

    void addPredicate(std::shared_ptr<binder::Expression> predicate);
    std::shared_ptr<binder::Expression> popNodePKEqualityComparison(
        const binder::Expression& nodeID);
    binder::expression_vector getAllPredicates();

private:
    PredicateSet(const PredicateSet& other)
        : equalityPredicates{other.equalityPredicates},
          nonEqualityPredicates{other.nonEqualityPredicates} {}
};

class FilterPushDownOptimizer {
public:
    explicit FilterPushDownOptimizer(main::ClientContext* context) : context{context} {
        predicateSet = PredicateSet();
    }
    explicit FilterPushDownOptimizer(main::ClientContext* context, PredicateSet predicateSet)
        : predicateSet{std::move(predicateSet)}, context{context} {}

    void rewrite(planner::LogicalPlan* plan);

private:
    std::shared_ptr<planner::LogicalOperator> visitOperator(
        const std::shared_ptr<planner::LogicalOperator>& op);
    // Collect predicates in FILTER
    std::shared_ptr<planner::LogicalOperator> visitFilterReplace(
        const std::shared_ptr<planner::LogicalOperator>& op);
    // Push primary key lookup into CROSS_PRODUCT
    // E.g.
    //      Filter(a.ID=b.ID)
    //      CrossProduct                   to                  HashJoin
    //   S(a)           S(b)                            S(a)             S(b)
    std::shared_ptr<planner::LogicalOperator> visitCrossProductReplace(
        const std::shared_ptr<planner::LogicalOperator>& op);

    // Push FILTER into SCAN_NODE_TABLE, and turn index lookup into INDEX_SCAN.
    std::shared_ptr<planner::LogicalOperator> visitScanNodeTableReplace(
        const std::shared_ptr<planner::LogicalOperator>& op);
    // Push Filter into EXTEND.
    std::shared_ptr<planner::LogicalOperator> visitExtendReplace(
        const std::shared_ptr<planner::LogicalOperator>& op);
    // Push Filter into TABLE_FUNCTION_CALL
    std::shared_ptr<planner::LogicalOperator> visitTableFunctionCallReplace(
        const std::shared_ptr<planner::LogicalOperator>& op);

    // Finish the current push down optimization by apply remaining predicates as a single filter.
    // And heuristically reorder equality predicates first in the filter.
    std::shared_ptr<planner::LogicalOperator> finishPushDown(
        std::shared_ptr<planner::LogicalOperator> op);
    std::shared_ptr<planner::LogicalOperator> appendFilters(
        const binder::expression_vector& predicates,
        std::shared_ptr<planner::LogicalOperator> child);

    std::shared_ptr<planner::LogicalOperator> appendScanNodeTable(
        std::shared_ptr<binder::Expression> nodeID, std::vector<common::table_id_t> nodeTableIDs,
        binder::expression_vector properties, std::shared_ptr<planner::LogicalOperator> child);
    std::shared_ptr<planner::LogicalOperator> appendFilter(
        std::shared_ptr<binder::Expression> predicate,
        std::shared_ptr<planner::LogicalOperator> child);

    std::shared_ptr<planner::LogicalOperator> visitChildren(
        const std::shared_ptr<planner::LogicalOperator>& op);

private:
    PredicateSet predicateSet;
    main::ClientContext* context;
};

} // namespace optimizer
} // namespace lbug
