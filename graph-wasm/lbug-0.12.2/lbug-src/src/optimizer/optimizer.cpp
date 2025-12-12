#include "optimizer/optimizer.h"

#include "main/client_context.h"
#include "optimizer/acc_hash_join_optimizer.h"
#include "optimizer/agg_key_dependency_optimizer.h"
#include "optimizer/cardinality_updater.h"
#include "optimizer/correlated_subquery_unnest_solver.h"
#include "optimizer/factorization_rewriter.h"
#include "optimizer/filter_push_down_optimizer.h"
#include "optimizer/limit_push_down_optimizer.h"
#include "optimizer/projection_push_down_optimizer.h"
#include "optimizer/remove_factorization_rewriter.h"
#include "optimizer/remove_unnecessary_join_optimizer.h"
#include "optimizer/schema_populator.h"
#include "optimizer/top_k_optimizer.h"
#include "planner/operator/logical_explain.h"
#include "transaction/transaction.h"

namespace lbug {
namespace optimizer {

void Optimizer::optimize(planner::LogicalPlan* plan, main::ClientContext* context,
    const planner::CardinalityEstimator& cardinalityEstimator) {
    if (context->getClientConfig()->enablePlanOptimizer) {
        // Factorization structure should be removed before further optimization can be applied.
        auto removeFactorizationRewriter = RemoveFactorizationRewriter();
        removeFactorizationRewriter.rewrite(plan);

        auto correlatedSubqueryUnnestSolver = CorrelatedSubqueryUnnestSolver(nullptr);
        correlatedSubqueryUnnestSolver.solve(plan->getLastOperator().get());

        auto removeUnnecessaryJoinOptimizer = RemoveUnnecessaryJoinOptimizer();
        removeUnnecessaryJoinOptimizer.rewrite(plan);

        auto filterPushDownOptimizer = FilterPushDownOptimizer(context);
        filterPushDownOptimizer.rewrite(plan);

        auto projectionPushDownOptimizer =
            ProjectionPushDownOptimizer(context->getClientConfig()->recursivePatternSemantic);
        projectionPushDownOptimizer.rewrite(plan);

        auto limitPushDownOptimizer = LimitPushDownOptimizer();
        limitPushDownOptimizer.rewrite(plan);

        if (context->getClientConfig()->enableSemiMask) {
            // HashJoinSIPOptimizer should be applied after optimizers that manipulate hash join.
            auto hashJoinSIPOptimizer = HashJoinSIPOptimizer();
            hashJoinSIPOptimizer.rewrite(plan);
        }

        auto topKOptimizer = TopKOptimizer();
        topKOptimizer.rewrite(plan);

        auto factorizationRewriter = FactorizationRewriter();
        factorizationRewriter.rewrite(plan);

        // AggKeyDependencyOptimizer doesn't change factorization structure and thus can be put
        // after FactorizationRewriter.
        auto aggKeyDependencyOptimizer = AggKeyDependencyOptimizer();
        aggKeyDependencyOptimizer.rewrite(plan);

        // for EXPLAIN LOGICAL we need to update the cardinalities for the optimized plan
        // we don't need to do this otherwise as we don't use the cardinalities after planning
        if (plan->getLastOperatorRef().getOperatorType() == planner::LogicalOperatorType::EXPLAIN) {
            const auto& explain = plan->getLastOperatorRef().cast<planner::LogicalExplain>();
            if (explain.getExplainType() == common::ExplainType::LOGICAL_PLAN) {
                auto cardinalityUpdater = CardinalityUpdater(cardinalityEstimator,
                    transaction::Transaction::Get(*context));
                cardinalityUpdater.rewrite(plan);
            }
        }
    } else {
        // we still need to compute the schema for each operator even if we have optimizations
        // disabled
        auto schemaPopulator = SchemaPopulator{};
        schemaPopulator.rewrite(plan);
    }
}

} // namespace optimizer
} // namespace lbug
