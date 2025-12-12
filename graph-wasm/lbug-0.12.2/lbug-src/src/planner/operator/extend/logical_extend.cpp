#include "planner/operator/extend/logical_extend.h"

namespace lbug {
namespace planner {

void LogicalExtend::computeFactorizedSchema() {
    copyChildSchema(0);
    const auto boundGroupPos = schema->getGroupPos(*boundNode->getInternalID());
    if (!schema->getGroup(boundGroupPos)->isFlat()) {
        schema->flattenGroup(boundGroupPos);
    }
    uint32_t nbrGroupPos = 0u;
    nbrGroupPos = schema->createGroup();
    schema->insertToGroupAndScope(nbrNode->getInternalID(), nbrGroupPos);
    for (auto& property : properties) {
        schema->insertToGroupAndScope(property, nbrGroupPos);
    }
    if (rel->hasDirectionExpr()) {
        schema->insertToGroupAndScope(rel->getDirectionExpr(), nbrGroupPos);
    }
}

void LogicalExtend::computeFlatSchema() {
    copyChildSchema(0);
    schema->insertToGroupAndScope(nbrNode->getInternalID(), 0);
    for (auto& property : properties) {
        schema->insertToGroupAndScope(property, 0);
    }
    if (rel->hasDirectionExpr()) {
        schema->insertToGroupAndScope(rel->getDirectionExpr(), 0);
    }
}

std::unique_ptr<LogicalOperator> LogicalExtend::copy() {
    auto extend = std::make_unique<LogicalExtend>(boundNode, nbrNode, rel, direction,
        extendFromSource_, properties, children[0]->copy(), cardinality);
    extend->setPropertyPredicates(copyVector(propertyPredicates));
    extend->scanNbrID = scanNbrID;
    return extend;
}

} // namespace planner
} // namespace lbug
