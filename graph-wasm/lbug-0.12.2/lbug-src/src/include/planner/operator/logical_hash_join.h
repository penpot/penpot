#pragma once

#include "common/enums/join_type.h"
#include "logical_operator.h"
#include "planner/operator/sip/side_way_info_passing.h"

namespace lbug {
namespace planner {

// We only support equality comparison as join condition
using join_condition_t = binder::expression_pair;

// Probe side on left, i.e. children[0]. Build side on right, i.e. children[1].
class LBUG_API LogicalHashJoin final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::HASH_JOIN;

public:
    LogicalHashJoin(std::vector<join_condition_t> joinConditions, common::JoinType joinType,
        std::shared_ptr<binder::Expression> mark, std::shared_ptr<LogicalOperator> probeChild,
        std::shared_ptr<LogicalOperator> buildChild, common::cardinality_t cardinality = 0)
        : LogicalOperator{type_, std::move(probeChild), std::move(buildChild)},
          joinConditions(std::move(joinConditions)), joinType{joinType}, mark{std::move(mark)} {
        this->cardinality = cardinality;
    }

    f_group_pos_set getGroupsPosToFlattenOnProbeSide();
    f_group_pos_set getGroupsPosToFlattenOnBuildSide();

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override;

    binder::expression_vector getExpressionsToMaterialize() const;

    binder::expression_vector getJoinNodeIDs() const;
    static binder::expression_vector getJoinNodeIDs(
        const std::vector<join_condition_t>& joinConditions);

    std::vector<join_condition_t> getJoinConditions() const { return joinConditions; }
    common::JoinType getJoinType() const { return joinType; }
    bool hasMark() const { return mark != nullptr; }
    std::shared_ptr<binder::Expression> getMark() const { return mark; }

    SIPInfo& getSIPInfoUnsafe() { return sipInfo; }
    SIPInfo getSIPInfo() const { return sipInfo; }

    std::unique_ptr<LogicalOperator> copy() override;

    // Flat probe side key group in either of the following two cases:
    // 1. there are multiple join nodes;
    // 2. if the build side contains more than one group or the build side has projected out data
    // chunks, which may increase the multiplicity of data chunks in the build side. The key is to
    // keep probe side key unflat only when we know that there is only 0 or 1 match for each key.
    // TODO(Guodong): when the build side has only flat payloads, we should consider getting rid of
    // flattening probe key, instead duplicating keys as in vectorized processing if necessary.
    bool requireFlatProbeKeys() const;

    static bool isNodeIDOnlyJoin(const std::vector<join_condition_t>& joinConditions);

private:
    std::vector<join_condition_t> joinConditions;
    common::JoinType joinType;
    std::shared_ptr<binder::Expression> mark; // when joinType is Mark or Left
    SIPInfo sipInfo;
};

} // namespace planner
} // namespace lbug
