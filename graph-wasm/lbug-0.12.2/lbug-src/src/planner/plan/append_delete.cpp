#include "binder/query/updating_clause/bound_delete_info.h"
#include "planner/operator/persistent/logical_delete.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendDelete(const std::vector<BoundDeleteInfo>& infos, LogicalPlan& plan) {
    auto delete_ = std::make_shared<LogicalDelete>(copyVector(infos), plan.getLastOperator());
    appendFlattens(delete_->getGroupsPosToFlatten(), plan);
    delete_->setChild(0, plan.getLastOperator());
    delete_->computeFactorizedSchema();
    plan.setLastOperator(std::move(delete_));
}

} // namespace planner
} // namespace lbug
