#include "binder/expression/node_expression.h"
#include "graph/on_disk_graph.h"
#include "planner/operator/extend/logical_recursive_extend.h"
#include "planner/operator/sip/logical_semi_masker.h"
#include "processor/operator/recursive_extend.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;
using namespace lbug::graph;
using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace processor {

std::unique_ptr<NodeOffsetMaskMap> createNodeOffsetMaskMap(const Expression& expr,
    PlanMapper* mapper) {
    auto& node = expr.constCast<NodeExpression>();
    auto maskMap = std::make_unique<NodeOffsetMaskMap>();
    for (auto tableID : node.getTableIDs()) {
        maskMap->addMask(tableID, mapper->createSemiMask(tableID));
    }
    return maskMap;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapRecursiveExtend(
    const LogicalOperator* logicalOperator) {
    auto& extend = logicalOperator->constCast<LogicalRecursiveExtend>();
    auto& bindData = extend.getBindData();
    auto columns = extend.getResultColumns();
    auto tableSchema = createFlatFTableSchema(columns, *extend.getSchema());
    auto table = std::make_shared<FactorizedTable>(storage::MemoryManager::Get(*clientContext),
        tableSchema.copy());
    auto graph = std::make_unique<OnDiskGraph>(clientContext, bindData.graphEntry.copy());
    auto sharedState =
        std::make_shared<RecursiveExtendSharedState>(table, std::move(graph), extend.getLimitNum());
    if (extend.hasInputNodeMask()) {
        sharedState->setInputNodeMask(createNodeOffsetMaskMap(*bindData.nodeInput, this));
    }
    if (extend.hasOutputNodeMask()) {
        sharedState->setOutputNodeMask(createNodeOffsetMaskMap(*bindData.nodeOutput, this));
    }
    auto printInfo =
        std::make_unique<RecursiveExtendPrintInfo>(extend.getFunction().getFunctionName());
    auto recursiveExtend = std::make_unique<RecursiveExtend>(extend.getFunction().copy(), bindData,
        sharedState, getOperatorID(), std::move(printInfo));
    // Map node predicate pipeline
    if (extend.hasNodePredicate()) {
        addOperatorMapping(logicalOperator, recursiveExtend.get());
        sharedState->setPathNodeMask(std::make_unique<NodeOffsetMaskMap>());
        auto maskMap = sharedState->getPathNodeMaskMap();
        KU_ASSERT(extend.getNumChildren() == 1);
        auto logicalRoot = extend.getChild(0);
        KU_ASSERT(logicalRoot->getNumChildren() == 1 &&
                  logicalRoot->getChild(0)->getOperatorType() == LogicalOperatorType::SEMI_MASKER);
        auto logicalSemiMasker = logicalRoot->getChild(0)->ptrCast<LogicalSemiMasker>();
        logicalSemiMasker->addTarget(logicalOperator);
        for (auto tableID : logicalSemiMasker->getNodeTableIDs()) {
            maskMap->addMask(tableID, createSemiMask(tableID));
        }
        auto root = mapOperator(logicalRoot.get());
        recursiveExtend->addChild(std::move(root));
        eraseOperatorMapping(logicalOperator);
    }
    logicalOpToPhysicalOpMap.insert({logicalOperator, recursiveExtend.get()});
    physical_op_vector_t children;
    children.push_back(std::move(recursiveExtend));
    return createFTableScanAligned(columns, extend.getSchema(), table, DEFAULT_VECTOR_CAPACITY,
        std::move(children));
}

} // namespace processor
} // namespace lbug
