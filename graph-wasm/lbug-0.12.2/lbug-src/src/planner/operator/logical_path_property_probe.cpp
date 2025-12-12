#include "planner/operator/logical_path_property_probe.h"

#include "optimizer/factorization_rewriter.h"
#include "optimizer/remove_factorization_rewriter.h"

namespace lbug {
namespace planner {

void LogicalPathPropertyProbe::computeFactorizedSchema() {
    copyChildSchema(0);
    if (pathNodeIDs != nullptr) {
        KU_ASSERT(schema->getNumGroups() == 1);
        schema->insertToGroupAndScope(recursiveRel, 0);
    }

    if (nodeChild != nullptr) {
        auto rewriter = optimizer::FactorizationRewriter();
        rewriter.visitOperator(nodeChild.get());
    }
    if (relChild != nullptr) {
        auto rewriter = optimizer::FactorizationRewriter();
        rewriter.visitOperator(relChild.get());
    }
}

void LogicalPathPropertyProbe::computeFlatSchema() {
    copyChildSchema(0);
    if (pathNodeIDs != nullptr) {
        KU_ASSERT(schema->getNumGroups() == 1);
        schema->insertToGroupAndScope(recursiveRel, 0);
    }

    if (nodeChild != nullptr) {
        auto rewriter = optimizer::RemoveFactorizationRewriter();
        rewriter.visitOperator(nodeChild);
    }
    if (relChild != nullptr) {
        auto rewriter = optimizer::RemoveFactorizationRewriter();
        rewriter.visitOperator(relChild);
    }
}

std::unique_ptr<LogicalOperator> LogicalPathPropertyProbe::copy() {
    auto nodeChildCopy = nodeChild == nullptr ? nullptr : nodeChild->copy();
    auto relChildCopy = relChild == nullptr ? nullptr : relChild->copy();
    auto op = std::make_unique<LogicalPathPropertyProbe>(recursiveRel, children[0]->copy(),
        std::move(nodeChildCopy), std::move(relChildCopy), joinType);
    op->sipInfo = sipInfo;
    op->direction = direction;
    op->extendFromLeft = extendFromLeft;
    op->pathNodeIDs = pathNodeIDs;
    op->pathEdgeIDs = pathEdgeIDs;
    return op;
}

} // namespace planner
} // namespace lbug
