#include "binder/expression/expression_util.h"
#include "planner/operator/logical_intersect.h"
#include "processor/operator/intersect/intersect.h"
#include "processor/operator/intersect/intersect_build.h"
#include "processor/plan_mapper.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::binder;
using namespace lbug::planner;
using namespace lbug::common;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapIntersect(const LogicalOperator* logicalOperator) {
    auto logicalIntersect = logicalOperator->constPtrCast<LogicalIntersect>();
    auto intersectNodeID = logicalIntersect->getIntersectNodeID();
    auto outSchema = logicalIntersect->getSchema();
    std::vector<std::shared_ptr<HashJoinSharedState>> sharedStates;
    std::vector<IntersectDataInfo> intersectDataInfos;
    // Map build side children.
    std::vector<std::unique_ptr<PhysicalOperator>> buildChildren;
    for (auto i = 1u; i < logicalIntersect->getNumChildren(); i++) {
        auto keyNodeID = logicalIntersect->getKeyNodeID(i - 1);
        auto keys = expression_vector{keyNodeID};
        auto buildSchema = logicalIntersect->getChild(i)->getSchema();
        auto buildPrevOperator = mapOperator(logicalIntersect->getChild(i).get());
        auto payloadExpressions =
            ExpressionUtil::excludeExpressions(buildSchema->getExpressionsInScope(), keys);
        auto buildInfo = createHashBuildInfo(*buildSchema, keys, payloadExpressions);
        auto globalHashTable =
            std::make_unique<JoinHashTable>(*storage::MemoryManager::Get(*clientContext),
                ExpressionUtil::getDataTypes(keys), buildInfo.tableSchema.copy());
        auto sharedState = std::make_shared<HashJoinSharedState>(std::move(globalHashTable));
        sharedStates.push_back(sharedState);
        auto printInfo = std::make_unique<IntersectBuildPrintInfo>(keys, payloadExpressions);
        auto build = std::make_unique<IntersectBuild>(sharedState, std::move(buildInfo),
            std::move(buildPrevOperator), getOperatorID(), std::move(printInfo));
        build->setDescriptor(std::make_unique<ResultSetDescriptor>(buildSchema));
        buildChildren.push_back(std::move(build));
        // Collect intersect info
        std::vector<DataPos> vectorsToScanPos;
        auto expressionsToScan = ExpressionUtil::excludeExpressions(
            buildSchema->getExpressionsInScope(), {keyNodeID, intersectNodeID});
        for (auto& expression : expressionsToScan) {
            vectorsToScanPos.emplace_back(outSchema->getExpressionPos(*expression));
        }
        IntersectDataInfo info{DataPos(outSchema->getExpressionPos(*keyNodeID)), vectorsToScanPos};
        intersectDataInfos.push_back(info);
    }
    // Map probe side child.
    auto probeChild = mapOperator(logicalIntersect->getChild(0).get());
    // Map intersect.
    auto outputDataPos =
        DataPos(outSchema->getExpressionPos(*logicalIntersect->getIntersectNodeID()));
    auto printInfo = std::make_unique<IntersectPrintInfo>(intersectNodeID);
    auto intersect = make_unique<Intersect>(outputDataPos, intersectDataInfos, sharedStates,
        std::move(probeChild), getOperatorID(), std::move(printInfo));
    for (auto& child : buildChildren) {
        intersect->addChild(std::move(child));
    }
    if (logicalIntersect->getSIPInfo().direction == SIPDirection::PROBE_TO_BUILD) {
        mapSIPJoin(intersect.get());
    }
    return intersect;
}

} // namespace processor
} // namespace lbug
