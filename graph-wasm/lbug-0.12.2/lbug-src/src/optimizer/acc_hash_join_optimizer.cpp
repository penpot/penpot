#include "optimizer/acc_hash_join_optimizer.h"

#include "catalog/catalog_entry/table_catalog_entry.h"
#include "optimizer/logical_operator_collector.h"
#include "planner/operator/extend/logical_recursive_extend.h"
#include "planner/operator/logical_accumulate.h"
#include "planner/operator/logical_hash_join.h"
#include "planner/operator/logical_intersect.h"
#include "planner/operator/logical_path_property_probe.h"
#include "planner/operator/scan/logical_scan_node_table.h"
#include "planner/operator/sip/logical_semi_masker.h"

using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::planner;
using namespace lbug::function;

namespace lbug {
namespace optimizer {

static std::shared_ptr<LogicalOperator> appendAccumulate(std::shared_ptr<LogicalOperator> child) {
    auto accumulate = std::make_shared<LogicalAccumulate>(AccumulateType::REGULAR,
        expression_vector{}, nullptr /* mark */, std::move(child));
    accumulate->computeFlatSchema();
    return accumulate;
}

static table_id_vector_t getTableIDs(const std::vector<catalog::TableCatalogEntry*>& entries) {
    table_id_vector_t result;
    for (auto& entry : entries) {
        result.push_back(entry->getTableID());
    }
    return result;
}

static std::vector<table_id_t> getTableIDs(const LogicalOperator* op,
    SemiMaskTargetType targetType) {
    switch (op->getOperatorType()) {
    case LogicalOperatorType::SCAN_NODE_TABLE: {
        return op->constCast<LogicalScanNodeTable>().getTableIDs();
    }
    case LogicalOperatorType::RECURSIVE_EXTEND: {
        auto& bindData = op->constCast<LogicalRecursiveExtend>().getBindData();
        switch (targetType) {
        case SemiMaskTargetType::RECURSIVE_EXTEND_INPUT_NODE: {
            auto& node = bindData.nodeInput->constCast<NodeExpression>();
            return getTableIDs(node.getEntries());
        }
        case SemiMaskTargetType::RECURSIVE_EXTEND_OUTPUT_NODE: {
            auto& node = bindData.nodeOutput->constCast<NodeExpression>();
            return getTableIDs(node.getEntries());
        }
        default:
            KU_UNREACHABLE;
        }
    }
    default:
        KU_UNREACHABLE;
    }
}

static bool sameTableIDs(const std::unordered_set<table_id_t>& set,
    const std::vector<table_id_t>& ids) {
    if (set.size() != ids.size()) {
        return false;
    }
    for (auto id : ids) {
        if (!set.contains(id)) {
            return false;
        }
    }
    return true;
}

static bool haveSameTableIDs(const std::vector<LogicalOperator*>& ops,
    SemiMaskTargetType targetType) {
    std::unordered_set<table_id_t> tableIDSet;
    for (auto id : getTableIDs(ops[0], targetType)) {
        tableIDSet.insert(id);
    }
    for (auto i = 0u; i < ops.size(); ++i) {
        if (!sameTableIDs(tableIDSet, getTableIDs(ops[i], targetType))) {
            return false;
        }
    }
    return true;
}

static bool haveSameType(const std::vector<LogicalOperator*>& ops) {
    for (auto i = 0u; i < ops.size(); ++i) {
        if (ops[i]->getOperatorType() != ops[0]->getOperatorType()) {
            return false;
        }
    }
    return true;
}

bool sanityCheckCandidates(const std::vector<LogicalOperator*>& ops,
    SemiMaskTargetType targetType) {
    KU_ASSERT(!ops.empty());
    if (!haveSameType(ops)) {
        return false;
    }
    if (!haveSameTableIDs(ops, targetType)) {
        return false;
    }
    return true;
}

static std::shared_ptr<LogicalSemiMasker> appendSemiMasker(SemiMaskKeyType keyType,
    SemiMaskTargetType targetType, std::shared_ptr<Expression> key,
    std::vector<LogicalOperator*> candidates, std::shared_ptr<LogicalOperator> child) {
    auto tableIDs = getTableIDs(candidates[0], targetType);
    auto semiMasker =
        std::make_shared<LogicalSemiMasker>(keyType, targetType, key, tableIDs, child);
    for (auto candidate : candidates) {
        semiMasker->addTarget(candidate);
    }
    semiMasker->computeFlatSchema();
    return semiMasker;
}

void HashJoinSIPOptimizer::rewrite(const LogicalPlan* plan) {
    visitOperator(plan->getLastOperator().get());
}

void HashJoinSIPOptimizer::visitOperator(LogicalOperator* op) {
    // bottom up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        visitOperator(op->getChild(i).get());
    }
    visitOperatorSwitch(op);
}

static bool subPlanContainsFilter(LogicalOperator* root) {
    auto filterCollector = LogicalFilterCollector();
    filterCollector.collect(root);
    auto indexScanNodeCollector = LogicalIndexScanNodeCollector();
    indexScanNodeCollector.collect(root);
    if (!filterCollector.hasOperators() && !indexScanNodeCollector.hasOperators()) {
        return false;
    }
    return true;
}

// Probe side is qualified if it is selective.
static bool isProbeSideQualified(LogicalOperator* probeRoot) {
    if (probeRoot->getOperatorType() == LogicalOperatorType::ACCUMULATE) {
        // No Acc hash join if probe side has already been accumulated. This can be solved.
        return false;
    }
    // Probe side is not selective. So we don't apply acc hash join.
    return subPlanContainsFilter(probeRoot);
}

// Find all ScanNodeIDs under root which scans parameter nodeID. Note that there might be
// multiple ScanNodeIDs matches because both node and rel table scans will trigger scanNodeIDs.
static std::vector<LogicalOperator*> getScanNodeCandidates(const Expression& nodeID,
    LogicalOperator* root) {
    std::vector<LogicalOperator*> result;
    auto collector = LogicalScanNodeTableCollector();
    collector.collect(root);
    for (auto& op : collector.getOperators()) {
        auto& scan = op->constCast<LogicalScanNodeTable>();
        if (scan.getScanType() != LogicalScanNodeTableType::SCAN) {
            // Do not apply semi mask to index scan.
            continue;
        }
        if (nodeID.getUniqueName() == scan.getNodeID()->getUniqueName()) {
            result.push_back(op);
        }
    }
    return result;
}

static std::vector<LogicalOperator*> getRecursiveExtendInputNodeCandidates(const Expression& nodeID,
    LogicalOperator* root) {
    std::vector<LogicalOperator*> result;
    auto collector = LogicalRecursiveExtendCollector();
    collector.collect(root);
    for (auto& op : collector.getOperators()) {
        auto& recursiveExtend = op->constCast<LogicalRecursiveExtend>();
        auto& bindData = recursiveExtend.getBindData();
        if (nodeID == *bindData.nodeInput->constCast<NodeExpression>().getInternalID()) {
            result.push_back(op);
        }
    }
    return result;
}

static std::vector<LogicalOperator*> getRecursiveExtendOutputNodeCandidates(
    const Expression& nodeID, LogicalOperator* root) {
    std::vector<LogicalOperator*> result;
    auto collector = LogicalRecursiveExtendCollector();
    collector.collect(root);
    for (auto op : collector.getOperators()) {
        auto& recursiveExtend = op->constCast<LogicalRecursiveExtend>();
        auto& bindData = recursiveExtend.getBindData();
        if (nodeID == *bindData.nodeOutput->constCast<NodeExpression>().getInternalID()) {
            result.push_back(op);
        }
    }
    return result;
}

static std::shared_ptr<LogicalOperator> tryApplySemiMask(std::shared_ptr<Expression> nodeID,
    std::shared_ptr<LogicalOperator> fromRoot, LogicalOperator* toRoot) {
    // TODO(Xiyang): Check if a semi mask can/need to be applied to ScanNodeTable, RecursiveJoin &
    // GDS at the same time
    auto recursiveExtendInputNodeCandidates =
        getRecursiveExtendInputNodeCandidates(*nodeID, toRoot);
    if (!recursiveExtendInputNodeCandidates.empty()) {
        for (auto& op : recursiveExtendInputNodeCandidates) {
            op->cast<LogicalRecursiveExtend>().setInputNodeMask();
        }
        auto targetType = SemiMaskTargetType::RECURSIVE_EXTEND_INPUT_NODE;
        KU_ASSERT(sanityCheckCandidates(recursiveExtendInputNodeCandidates, targetType));
        return appendSemiMasker(SemiMaskKeyType::NODE, targetType, std::move(nodeID),
            recursiveExtendInputNodeCandidates, std::move(fromRoot));
    }
    auto recursiveExtendNodeCandidates = getRecursiveExtendOutputNodeCandidates(*nodeID, toRoot);
    if (!recursiveExtendNodeCandidates.empty()) {
        for (auto& op : recursiveExtendNodeCandidates) {
            op->cast<LogicalRecursiveExtend>().setOutputNodeMask();
        }
        auto targetType = SemiMaskTargetType::RECURSIVE_EXTEND_OUTPUT_NODE;
        KU_ASSERT(sanityCheckCandidates(recursiveExtendNodeCandidates, targetType));
        return appendSemiMasker(SemiMaskKeyType::NODE, targetType, std::move(nodeID),
            recursiveExtendNodeCandidates, std::move(fromRoot));
    }
    auto scanNodeCandidates = getScanNodeCandidates(*nodeID, toRoot);
    if (!scanNodeCandidates.empty()) {
        return appendSemiMasker(SemiMaskKeyType::NODE, SemiMaskTargetType::SCAN_NODE,
            std::move(nodeID), scanNodeCandidates, std::move(fromRoot));
    }
    return nullptr;
}

static bool tryProbeToBuildHJSIP(LogicalOperator* op) {
    auto& hashJoin = op->cast<LogicalHashJoin>();
    if (!isProbeSideQualified(op->getChild(0).get())) {
        return false;
    }
    auto probeRoot = hashJoin.getChild(0);
    auto buildRoot = hashJoin.getChild(1);
    auto hasSemiMaskApplied = false;
    for (auto& nodeID : hashJoin.getJoinNodeIDs()) {
        auto newProbeRoot = tryApplySemiMask(nodeID, probeRoot, buildRoot.get());
        if (newProbeRoot != nullptr) {
            probeRoot = newProbeRoot;
            hasSemiMaskApplied = true;
        }
    }
    if (!hasSemiMaskApplied) {
        return false;
    }
    auto& sipInfo = hashJoin.getSIPInfoUnsafe();
    sipInfo.position = SemiMaskPosition::ON_PROBE;
    sipInfo.dependency = SIPDependency::PROBE_DEPENDS_ON_BUILD;
    sipInfo.direction = SIPDirection::PROBE_TO_BUILD;
    hashJoin.setChild(0, appendAccumulate(probeRoot));
    return true;
}

static bool isBuildSideQualified(LogicalOperator* buildRoot) {
    if (subPlanContainsFilter(buildRoot)) {
        return true;
    }
    // TODO(Xiyang): this may not be the best solution. Most of the time we will pass a semi mask
    // to GDS (recursive join) operator and make it generate small result. Though there are also
    // exceptions. In such case we will pay a bit overhead.
    auto op = buildRoot;
    while (op->getNumChildren() == 1) {
        op = op->getChild(0).get();
    }
    return op->getOperatorType() == LogicalOperatorType::RECURSIVE_EXTEND;
}

static bool tryBuildToProbeHJSIP(LogicalOperator* op) {
    auto& hashJoin = op->cast<LogicalHashJoin>();
    if (hashJoin.getJoinType() != JoinType::INNER) {
        return false;
    }
    if (hashJoin.getSIPInfo().direction != SIPDirection::FORCE_BUILD_TO_PROBE &&
        !isBuildSideQualified(op->getChild(1).get())) {
        return false;
    }
    auto probeRoot = hashJoin.getChild(0);
    auto buildRoot = hashJoin.getChild(1);
    auto hasSemiMaskApplied = false;
    for (auto& nodeID : hashJoin.getJoinNodeIDs()) {
        auto newBuildRoot = tryApplySemiMask(nodeID, buildRoot, probeRoot.get());
        if (newBuildRoot != nullptr) {
            buildRoot = newBuildRoot;
            hasSemiMaskApplied = true;
        }
    }
    if (!hasSemiMaskApplied) {
        return false;
    }
    auto& sipInfo = hashJoin.getSIPInfoUnsafe();
    sipInfo.position = SemiMaskPosition::ON_BUILD;
    sipInfo.dependency = SIPDependency::BUILD_DEPENDS_ON_PROBE;
    sipInfo.direction = SIPDirection::BUILD_TO_PROBE;
    hashJoin.setChild(1, buildRoot);
    return true;
}

void HashJoinSIPOptimizer::visitHashJoin(LogicalOperator* op) {
    auto& hashJoin = op->cast<LogicalHashJoin>();
    if (LogicalOperatorUtils::isAccHashJoin(hashJoin)) {
        return;
    }
    if (hashJoin.getSIPInfo().position == SemiMaskPosition::PROHIBIT) {
        return;
    }
    if (tryBuildToProbeHJSIP(op)) { // Try build to probe SIP first.
        return;
    }
    if (hashJoin.getSIPInfo().position == SemiMaskPosition::PROHIBIT_PROBE_TO_BUILD) {
        return;
    }
    tryProbeToBuildHJSIP(op);
}

// TODO(Xiyang): we don't apply SIP from build to probe.
void HashJoinSIPOptimizer::visitIntersect(LogicalOperator* op) {
    auto& intersect = op->cast<LogicalIntersect>();
    switch (intersect.getSIPInfo().position) {
    case SemiMaskPosition::PROHIBIT_PROBE_TO_BUILD:
    case SemiMaskPosition::PROHIBIT:
        return;
    default:
        break;
    }
    if (!isProbeSideQualified(op->getChild(0).get())) {
        return;
    }
    auto probeRoot = intersect.getChild(0);
    auto hasSemiMaskApplied = false;
    for (auto& nodeID : intersect.getKeyNodeIDs()) {
        std::vector<LogicalOperator*> ops;
        for (auto i = 1u; i < intersect.getNumChildren(); ++i) {
            auto buildRoot = intersect.getChild(i);
            for (auto& op_ : getScanNodeCandidates(*nodeID, buildRoot.get())) {
                ops.push_back(op_);
            }
        }
        if (!ops.empty()) {
            probeRoot = appendSemiMasker(SemiMaskKeyType::NODE, SemiMaskTargetType::SCAN_NODE,
                nodeID, ops, probeRoot);
            hasSemiMaskApplied = true;
        }
    }
    if (!hasSemiMaskApplied) {
        return;
    }
    auto& sipInfo = intersect.getSIPInfoUnsafe();
    sipInfo.position = SemiMaskPosition::ON_PROBE;
    sipInfo.dependency = SIPDependency::PROBE_DEPENDS_ON_BUILD;
    sipInfo.direction = SIPDirection::PROBE_TO_BUILD;
    intersect.setChild(0, appendAccumulate(probeRoot));
}

void HashJoinSIPOptimizer::visitPathPropertyProbe(LogicalOperator* op) {
    auto& pathPropertyProbe = op->cast<LogicalPathPropertyProbe>();
    switch (pathPropertyProbe.getSIPInfo().position) {
    case SemiMaskPosition::PROHIBIT_PROBE_TO_BUILD:
    case SemiMaskPosition::PROHIBIT:
        return;
    default:
        break;
    }
    if (pathPropertyProbe.getJoinType() == RecursiveJoinType::TRACK_NONE) {
        return;
    }
    auto recursiveRel = pathPropertyProbe.getRel();
    auto nodeID = recursiveRel->getRecursiveInfo()->node->getInternalID();
    std::vector<LogicalOperator*> opsToApplySemiMask;
    if (pathPropertyProbe.getNodeChild() != nullptr) {
        auto child = pathPropertyProbe.getNodeChild().get();
        for (auto op_ : getScanNodeCandidates(*nodeID, child)) {
            opsToApplySemiMask.push_back(op_);
        }
    }
    if (pathPropertyProbe.getRelChild() != nullptr) {
        auto child = pathPropertyProbe.getRelChild().get();
        for (auto op_ : getScanNodeCandidates(*nodeID, child)) {
            opsToApplySemiMask.push_back(op_);
        }
    }
    if (opsToApplySemiMask.empty()) {
        return;
    }
    KU_ASSERT(
        pathPropertyProbe.getChild(0)->getOperatorType() == LogicalOperatorType::RECURSIVE_EXTEND);
    auto semiMasker = appendSemiMasker(SemiMaskKeyType::NODE_ID_LIST, SemiMaskTargetType::SCAN_NODE,
        recursiveRel->getRecursiveInfo()->bindData->pathNodeIDsExpr, opsToApplySemiMask,
        pathPropertyProbe.getChild(0));
    auto srcNodeID = recursiveRel->getSrcNode()->getInternalID();
    auto dstNodeID = recursiveRel->getDstNode()->getInternalID();
    semiMasker->setExtraKeyInfo(std::make_unique<ExtraNodeIDListKeyInfo>(srcNodeID, dstNodeID));
    pathPropertyProbe.setChild(0, semiMasker);

    auto& sipInfo = pathPropertyProbe.getSIPInfoUnsafe();
    sipInfo.position = SemiMaskPosition::ON_PROBE;
    sipInfo.dependency = SIPDependency::PROBE_DEPENDS_ON_BUILD;
    sipInfo.direction = SIPDirection::PROBE_TO_BUILD;
}

} // namespace optimizer
} // namespace lbug
