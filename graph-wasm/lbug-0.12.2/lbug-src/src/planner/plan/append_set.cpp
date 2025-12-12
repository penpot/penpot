#include "binder/query/updating_clause/bound_set_info.h"
#include "planner/operator/persistent/logical_set.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendSetProperty(const std::vector<BoundSetPropertyInfo>& infos, LogicalPlan& plan) {
    auto set = std::make_shared<LogicalSetProperty>(copyVector(infos), plan.getLastOperator());
    for (auto i = 0u; i < set->getInfos().size(); ++i) {
        auto groupsPos = set->getGroupsPosToFlatten(i);
        appendFlattens(groupsPos, plan);
        set->setChild(0, plan.getLastOperator());
    }
    set->computeFactorizedSchema();
    plan.setLastOperator(std::move(set));
}

} // namespace planner
} // namespace lbug
