#include "planner/operator/simple/logical_simple.h"

namespace lbug {
namespace planner {

void LogicalSimple::computeFlatSchema() {
    createEmptySchema();
}

void LogicalSimple::computeFactorizedSchema() {
    createEmptySchema();
}

} // namespace planner
} // namespace lbug
