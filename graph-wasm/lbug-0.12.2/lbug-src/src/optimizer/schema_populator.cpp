#include "optimizer/schema_populator.h"

namespace lbug::optimizer {

static void populateSchemaRecursive(planner::LogicalOperator* op) {
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        populateSchemaRecursive(op->getChild(i).get());
    }
    op->computeFactorizedSchema();
}

void SchemaPopulator::rewrite(planner::LogicalPlan* plan) {
    populateSchemaRecursive(plan->getLastOperator().get());
}
} // namespace lbug::optimizer
