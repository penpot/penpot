#pragma once

#include "binder/expression/rel_expression.h"
#include "planner/operator/extend/recursive_join_type.h"
#include "planner/operator/logical_operator.h"
#include "planner/operator/sip/side_way_info_passing.h"

namespace lbug {
namespace planner {

class LogicalPathPropertyProbe : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::PATH_PROPERTY_PROBE;

public:
    LogicalPathPropertyProbe(std::shared_ptr<binder::RelExpression> rel,
        std::shared_ptr<LogicalOperator> probeChild, std::shared_ptr<LogicalOperator> nodeChild,
        std::shared_ptr<LogicalOperator> relChild, RecursiveJoinType joinType)
        : LogicalOperator{type_, std::move(probeChild)}, recursiveRel{std::move(rel)},
          nodeChild{std::move(nodeChild)}, relChild{std::move(relChild)}, joinType{joinType} {}

    void computeFactorizedSchema() final;
    void computeFlatSchema() final;

    std::string getExpressionsForPrinting() const override { return recursiveRel->toString(); }

    std::shared_ptr<binder::RelExpression> getRel() const { return recursiveRel; }
    std::shared_ptr<binder::Expression> getPathNodeIDs() const { return pathNodeIDs; }
    std::shared_ptr<binder::Expression> getPathEdgeIDs() const { return pathEdgeIDs; }

    void setJoinType(RecursiveJoinType joinType_) { joinType = joinType_; }
    RecursiveJoinType getJoinType() const { return joinType; }

    std::shared_ptr<LogicalOperator> getNodeChild() const { return nodeChild; }
    std::shared_ptr<LogicalOperator> getRelChild() const { return relChild; }

    SIPInfo& getSIPInfoUnsafe() { return sipInfo; }
    SIPInfo getSIPInfo() const { return sipInfo; }

    std::unique_ptr<LogicalOperator> copy() override;

private:
    std::shared_ptr<binder::RelExpression> recursiveRel;
    std::shared_ptr<LogicalOperator> nodeChild;
    std::shared_ptr<LogicalOperator> relChild;
    RecursiveJoinType joinType;
    SIPInfo sipInfo;

public:
    common::ExtendDirection direction = common::ExtendDirection::FWD;
    bool extendFromLeft = true;
    std::shared_ptr<binder::Expression> pathNodeIDs;
    std::shared_ptr<binder::Expression> pathEdgeIDs;
};

} // namespace planner
} // namespace lbug
