#include "planner/operator/extend/logical_recursive_extend.h"

namespace lbug {
namespace planner {

void LogicalRecursiveExtend::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
    for (auto& expr : resultColumns) {
        schema->insertToGroupAndScope(expr, 0);
    }
}

void LogicalRecursiveExtend::computeFactorizedSchema() {
    createEmptySchema();
    auto pos = schema->createGroup();
    for (auto& e : resultColumns) {
        schema->insertToGroupAndScope(e, pos);
    }
}

} // namespace planner
} // namespace lbug
