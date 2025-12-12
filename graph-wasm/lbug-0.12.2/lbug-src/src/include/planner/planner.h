#pragma once

#include "binder/bound_statement.h"
#include "binder/query/query_graph.h"
#include "common/enums/accumulate_type.h"
#include "common/enums/extend_direction.h"
#include "common/enums/join_type.h"
#include "planner/join_order/cardinality_estimator.h"
#include "planner/join_order_enumerator_context.h"
#include "planner/operator/logical_plan.h"
#include "planner/operator/sip/semi_mask_target_type.h"

namespace lbug {
namespace extension {
class PlannerExtension;
}
namespace binder {
struct BoundTableScanInfo;
struct BoundCopyFromInfo;
struct BoundInsertInfo;
struct BoundSetPropertyInfo;
struct BoundDeleteInfo;
struct BoundJoinHintNode;
class NormalizedSingleQuery;
class NormalizedQueryPart;
class BoundReadingClause;
class BoundUpdatingClause;
class BoundProjectionBody;
} // namespace binder
namespace planner {

struct LogicalInsertInfo;

enum class SubqueryPlanningType : uint8_t {
    NONE = 0,
    UNNEST_CORRELATED = 1,
    CORRELATED = 2,
};

struct QueryGraphPlanningInfo {
    // Predicate info.
    binder::expression_vector predicates;
    // Subquery info.
    SubqueryPlanningType subqueryType = SubqueryPlanningType::NONE;
    binder::expression_vector corrExprs;
    cardinality_t corrExprsCard = 0;
    // Join hint info.
    std::shared_ptr<binder::BoundJoinHintNode> hint = nullptr;

    bool containsCorrExpr(const binder::Expression& expr) const;
};

// Group property expressions based on node/relationship.
class PropertyExprCollection {
public:
    void addProperties(const std::string& patternName,
        std::shared_ptr<binder::Expression> property);
    binder::expression_vector getProperties(const binder::Expression& pattern) const;
    binder::expression_vector getProperties() const;

    void clear();

private:
    std::unordered_map<std::string, binder::expression_vector> patternNameToProperties;
};

class LBUG_API Planner {
public:
    explicit Planner(main::ClientContext* clientContext);
    DELETE_COPY_AND_MOVE(Planner);

    LogicalPlan planStatement(const binder::BoundStatement& statement);

    // Plan simple statement.
    LogicalPlan planCreateTable(const binder::BoundStatement& statement);
    LogicalPlan planCreateType(const binder::BoundStatement& statement);
    LogicalPlan planCreateSequence(const binder::BoundStatement& statement);
    LogicalPlan planCreateMacro(const binder::BoundStatement& statement);
    LogicalPlan planDrop(const binder::BoundStatement& statement);
    LogicalPlan planAlter(const binder::BoundStatement& statement);
    LogicalPlan planStandaloneCall(const binder::BoundStatement& statement);
    LogicalPlan planStandaloneCallFunction(const binder::BoundStatement& statement);
    LogicalPlan planExplain(const binder::BoundStatement& statement);
    LogicalPlan planTransaction(const binder::BoundStatement& statement);
    LogicalPlan planExtension(const binder::BoundStatement& statement);
    LogicalPlan planAttachDatabase(const binder::BoundStatement& statement);
    LogicalPlan planDetachDatabase(const binder::BoundStatement& statement);
    LogicalPlan planUseDatabase(const binder::BoundStatement& statement);
    LogicalPlan planExtensionClause(const binder::BoundStatement& statement);

    // Plan copy.
    LogicalPlan planCopyTo(const binder::BoundStatement& statement);
    LogicalPlan planCopyFrom(const binder::BoundStatement& statement);
    LogicalPlan planCopyNodeFrom(const binder::BoundCopyFromInfo* info);
    LogicalPlan planCopyRelFrom(const binder::BoundCopyFromInfo* info);

    // Plan export/import database
    std::vector<std::shared_ptr<LogicalOperator>> planExportTableData(
        const binder::BoundStatement& boundExportDatabase);
    LogicalPlan planExportDatabase(const binder::BoundStatement& statement);
    LogicalPlan planImportDatabase(const binder::BoundStatement& statement);

    // Plan query.
    LogicalPlan planQuery(const binder::BoundStatement& boundStatement);
    LogicalPlan planSingleQuery(const binder::NormalizedSingleQuery& singleQuery);
    void planQueryPart(const binder::NormalizedQueryPart& queryPart, LogicalPlan& prevPlan);

    // Plan read.
    void planReadingClause(const binder::BoundReadingClause& readingClause, LogicalPlan& plan);
    void planMatchClause(const binder::BoundReadingClause& readingClause, LogicalPlan& plan);
    void planUnwindClause(const binder::BoundReadingClause& readingClause, LogicalPlan& plan);
    void planTableFunctionCall(const binder::BoundReadingClause& readingClause, LogicalPlan& plan);

    void planReadOp(std::shared_ptr<LogicalOperator> op,
        const binder::expression_vector& predicates, LogicalPlan& plan);
    void planLoadFrom(const binder::BoundReadingClause& readingClause, LogicalPlan& plan);

    // Plan updating
    void planUpdatingClause(const binder::BoundUpdatingClause& updatingClause, LogicalPlan& plan);
    void planInsertClause(const binder::BoundUpdatingClause& updatingClause, LogicalPlan& plan);
    void planMergeClause(const binder::BoundUpdatingClause& updatingClause, LogicalPlan& plan);
    void planSetClause(const binder::BoundUpdatingClause& updatingClause, LogicalPlan& plan);
    void planDeleteClause(const binder::BoundUpdatingClause& updatingClause, LogicalPlan& plan);

    // Plan projection
    void planProjectionBody(const binder::BoundProjectionBody* projectionBody, LogicalPlan& plan);
    void planAggregate(const binder::expression_vector& expressionsToAggregate,
        const binder::expression_vector& expressionsToGroupBy, LogicalPlan& plan);
    void planOrderBy(const binder::expression_vector& expressionsToProject,
        const binder::expression_vector& expressionsToOrderBy, const std::vector<bool>& isAscOrders,
        LogicalPlan& plan);

    // Plan subquery
    void planOptionalMatch(const binder::QueryGraphCollection& queryGraphCollection,
        const binder::expression_vector& predicates, LogicalPlan& leftPlan,
        std::shared_ptr<binder::BoundJoinHintNode> hint);
    // Write whether optional match succeed or not to mark.
    void planOptionalMatch(const binder::QueryGraphCollection& queryGraphCollection,
        const binder::expression_vector& predicates, std::shared_ptr<binder::Expression> mark,
        LogicalPlan& leftPlan, std::shared_ptr<binder::BoundJoinHintNode> hint);
    void planRegularMatch(const binder::QueryGraphCollection& queryGraphCollection,
        const binder::expression_vector& predicates, LogicalPlan& leftPlan,
        std::shared_ptr<binder::BoundJoinHintNode> hint);
    void planSubquery(const std::shared_ptr<binder::Expression>& subquery, LogicalPlan& outerPlan);
    void planSubqueryIfNecessary(std::shared_ptr<binder::Expression> expression, LogicalPlan& plan);

    static binder::expression_vector getCorrelatedExprs(
        const binder::QueryGraphCollection& collection, const binder::expression_vector& predicates,
        Schema* outerSchema);

    // Plan query graphs
    LogicalPlan planQueryGraphCollectionInNewContext(
        const binder::QueryGraphCollection& queryGraphCollection,
        const QueryGraphPlanningInfo& info);
    LogicalPlan planQueryGraphCollection(const binder::QueryGraphCollection& queryGraphCollection,
        const QueryGraphPlanningInfo& info);
    LogicalPlan planQueryGraph(const binder::QueryGraph& queryGraph,
        const QueryGraphPlanningInfo& info);

    // Plan node/rel table scan
    void planBaseTableScans(const QueryGraphPlanningInfo& info);
    void planCorrelatedExpressionsScan(const QueryGraphPlanningInfo& info);
    void planNodeScan(uint32_t nodePos);
    void planNodeIDScan(uint32_t nodePos);
    void planRelScan(uint32_t relPos);
    void appendExtend(std::shared_ptr<binder::NodeExpression> boundNode,
        std::shared_ptr<binder::NodeExpression> nbrNode, std::shared_ptr<binder::RelExpression> rel,
        common::ExtendDirection direction, const binder::expression_vector& properties,
        LogicalPlan& plan);

    // Plan dp level
    void planLevel(uint32_t level);
    void planLevelExactly(uint32_t level);
    void planLevelApproximately(uint32_t level);

    // Plan worst case optimal join
    void planWCOJoin(uint32_t leftLevel, uint32_t rightLevel);
    void planWCOJoin(const binder::SubqueryGraph& subgraph,
        const std::vector<std::shared_ptr<binder::RelExpression>>& rels,
        const std::shared_ptr<binder::NodeExpression>& intersectNode);

    // Plan index-nested-loop join / hash join
    void planInnerJoin(uint32_t leftLevel, uint32_t rightLevel);
    bool tryPlanINLJoin(const binder::SubqueryGraph& subgraph,
        const binder::SubqueryGraph& otherSubgraph,
        const std::vector<std::shared_ptr<binder::NodeExpression>>& joinNodes);
    void planInnerHashJoin(const binder::SubqueryGraph& subgraph,
        const binder::SubqueryGraph& otherSubgraph,
        const std::vector<std::shared_ptr<binder::NodeExpression>>& joinNodes, bool flipPlan);

    // Plan semi mask
    void appendNodeSemiMask(SemiMaskTargetType targetType, const binder::NodeExpression& node,
        LogicalPlan& plan);
    LogicalPlan getNodeSemiMaskPlan(SemiMaskTargetType targetType,
        const binder::NodeExpression& node, std::shared_ptr<binder::Expression> nodePredicate);

    // This is mostly used when we try to reinterpret function output as node and read its
    // properties, e.g. query_vector_index, gds algorithms ...
    LogicalPlan getNodePropertyScanPlan(const binder::NodeExpression& node);

    // Append dummy sink
    void appendDummySink(LogicalPlan& plan);

    // Append empty result
    void appendEmptyResult(LogicalPlan& plan);

    // Append updating operators
    void appendInsertNode(const std::vector<const binder::BoundInsertInfo*>& boundInsertInfos,
        LogicalPlan& plan);
    void appendInsertRel(const std::vector<const binder::BoundInsertInfo*>& boundInsertInfos,
        LogicalPlan& plan);

    void appendSetProperty(const std::vector<binder::BoundSetPropertyInfo>& infos,
        LogicalPlan& plan);
    void appendDelete(const std::vector<binder::BoundDeleteInfo>& infos, LogicalPlan& plan);
    std::unique_ptr<LogicalInsertInfo> createLogicalInsertInfo(
        const binder::BoundInsertInfo* info) const;

    // Append projection operators
    void appendProjection(const binder::expression_vector& expressionsToProject, LogicalPlan& plan);
    void appendAggregate(const binder::expression_vector& expressionsToGroupBy,
        const binder::expression_vector& expressionsToAggregate, LogicalPlan& plan);
    void appendOrderBy(const binder::expression_vector& expressions,
        const std::vector<bool>& isAscOrders, LogicalPlan& plan);
    void appendMultiplicityReducer(LogicalPlan& plan);
    void appendLimit(std::shared_ptr<binder::Expression> skipNum,
        std::shared_ptr<binder::Expression> limitNum, LogicalPlan& plan);

    // Append scan operators
    void appendExpressionsScan(const binder::expression_vector& expressions, LogicalPlan& plan);
    void appendScanNodeTable(std::shared_ptr<binder::Expression> nodeID,
        std::vector<common::table_id_t> tableIDs, const binder::expression_vector& properties,
        LogicalPlan& plan);

    // Append extend operators
    void appendNonRecursiveExtend(const std::shared_ptr<binder::NodeExpression>& boundNode,
        const std::shared_ptr<binder::NodeExpression>& nbrNode,
        const std::shared_ptr<binder::RelExpression>& rel, common::ExtendDirection direction,
        bool extendFromSource, const binder::expression_vector& properties, LogicalPlan& plan);
    void appendRecursiveExtend(const std::shared_ptr<binder::NodeExpression>& boundNode,
        const std::shared_ptr<binder::NodeExpression>& nbrNode,
        const std::shared_ptr<binder::RelExpression>& rel, common::ExtendDirection direction,
        LogicalPlan& plan);
    void createPathNodePropertyScanPlan(const std::shared_ptr<binder::NodeExpression>& node,
        const binder::expression_vector& properties, LogicalPlan& plan);
    void createPathRelPropertyScanPlan(const std::shared_ptr<binder::NodeExpression>& boundNode,
        const std::shared_ptr<binder::NodeExpression>& nbrNode,
        const std::shared_ptr<binder::RelExpression>& recursiveRel,
        common::ExtendDirection direction, bool extendFromSource,
        const binder::expression_vector& properties, LogicalPlan& plan);
    void appendNodeLabelFilter(std::shared_ptr<binder::Expression> nodeID,
        std::unordered_set<common::table_id_t> tableIDSet, LogicalPlan& plan);

    // Append Join operators
    void appendHashJoin(const binder::expression_vector& joinNodeIDs, common::JoinType joinType,
        LogicalPlan& probePlan, LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendHashJoin(const binder::expression_vector& joinNodeIDs, common::JoinType joinType,
        std::shared_ptr<binder::Expression> mark, LogicalPlan& probePlan, LogicalPlan& buildPlan,
        LogicalPlan& resultPlan);
    void appendHashJoin(const std::vector<binder::expression_pair>& joinConditions,
        common::JoinType joinType, std::shared_ptr<binder::Expression> mark, LogicalPlan& probePlan,
        LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendAccHashJoin(const std::vector<binder::expression_pair>& joinConditions,
        common::JoinType joinType, std::shared_ptr<binder::Expression> mark, LogicalPlan& probePlan,
        LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendMarkJoin(const binder::expression_vector& joinNodeIDs,
        const std::shared_ptr<binder::Expression>& mark, LogicalPlan& probePlan,
        LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendMarkJoin(const std::vector<binder::expression_pair>& joinConditions,
        const std::shared_ptr<binder::Expression>& mark, LogicalPlan& probePlan,
        LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendIntersect(const std::shared_ptr<binder::Expression>& intersectNodeID,
        binder::expression_vector& boundNodeIDs, LogicalPlan& probePlan,
        std::vector<LogicalPlan>& buildPlans);

    void appendCrossProduct(const LogicalPlan& probePlan, const LogicalPlan& buildPlan,
        LogicalPlan& resultPlan);
    // Optional cross product produce at least one tuple for each probe tuple
    void appendOptionalCrossProduct(std::shared_ptr<binder::Expression> mark,
        const LogicalPlan& probePlan, const LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendAccOptionalCrossProduct(std::shared_ptr<binder::Expression> mark,
        LogicalPlan& probePlan, const LogicalPlan& buildPlan, LogicalPlan& resultPlan);
    void appendCrossProduct(common::AccumulateType accumulateType,
        std::shared_ptr<binder::Expression> mark, const LogicalPlan& probePlan,
        const LogicalPlan& buildPlan, LogicalPlan& resultPlan);

    // Append accumulate operators
    // Skip if plan has been accumulated.
    void tryAppendAccumulate(LogicalPlan& plan);
    // Accumulate everything.
    void appendAccumulate(LogicalPlan& plan);
    // Accumulate everything. Append mark.
    void appendOptionalAccumulate(std::shared_ptr<binder::Expression> mark, LogicalPlan& plan);
    // Append accumulate with a set of expressions being flattened first.
    void appendAccumulate(const binder::expression_vector& flatExprs, LogicalPlan& plan);
    // Append accumulate with a set of expressions being flattened first. Append mark.
    void appendAccumulate(common::AccumulateType accumulateType,
        const binder::expression_vector& flatExprs, std::shared_ptr<binder::Expression> mark,
        LogicalPlan& plan);

    void appendDummyScan(LogicalPlan& plan);

    void appendUnwind(const binder::BoundReadingClause& boundReadingClause, LogicalPlan& plan);

    void appendFlattens(const f_group_pos_set& groupsPos, LogicalPlan& plan);
    void appendFlattenIfNecessary(f_group_pos groupPos, LogicalPlan& plan);

    void appendFilters(const binder::expression_vector& predicates, LogicalPlan& plan);
    void appendFilter(const std::shared_ptr<binder::Expression>& predicate, LogicalPlan& plan);

    void appendTableFunctionCall(const binder::BoundTableScanInfo& info, LogicalPlan& plan);

    void appendDistinct(const binder::expression_vector& keys, LogicalPlan& plan);

    const CardinalityEstimator& getCardinalityEstimator() const { return cardinalityEstimator; }
    CardinalityEstimator& getCardinliatyEstimatorUnsafe() { return cardinalityEstimator; }

    // Get operators
    static std::shared_ptr<LogicalOperator> getTableFunctionCall(
        const binder::BoundTableScanInfo& info);
    static std::shared_ptr<LogicalOperator> getTableFunctionCall(
        const binder::BoundReadingClause& readingClause);

    LogicalPlan createUnionPlan(std::vector<LogicalPlan>& childrenPlans,
        const binder::expression_vector& expressions, bool isUnionAll);

    binder::expression_vector getProperties(const binder::Expression& pattern) const;

    JoinOrderEnumeratorContext enterNewContext();
    void exitContext(JoinOrderEnumeratorContext prevContext);
    PropertyExprCollection enterNewPropertyExprCollection();
    void exitPropertyExprCollection(PropertyExprCollection collection);

    static binder::expression_vector getNewlyMatchedExprs(
        const std::vector<binder::SubqueryGraph>& prevs, const binder::SubqueryGraph& new_,
        const binder::expression_vector& exprs);
    static binder::expression_vector getNewlyMatchedExprs(const binder::SubqueryGraph& prev,
        const binder::SubqueryGraph& new_, const binder::expression_vector& exprs);
    static binder::expression_vector getNewlyMatchedExprs(const binder::SubqueryGraph& leftPrev,
        const binder::SubqueryGraph& rightPrev, const binder::SubqueryGraph& new_,
        const binder::expression_vector& exprs);

private:
    main::ClientContext* clientContext;
    PropertyExprCollection propertyExprCollection;
    CardinalityEstimator cardinalityEstimator;
    JoinOrderEnumeratorContext context;
    std::vector<extension::PlannerExtension*> plannerExtensions;
};

} // namespace planner
} // namespace lbug
