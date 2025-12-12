#include "binder/query/reading_clause/bound_unwind_clause.h"
#include "planner/operator/logical_unwind.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

void Planner::appendUnwind(const BoundReadingClause& readingClause, LogicalPlan& plan) {
    auto& unwindClause = ku_dynamic_cast<const BoundUnwindClause&>(readingClause);
    auto unwind = make_shared<LogicalUnwind>(unwindClause.getInExpr(), unwindClause.getOutExpr(),
        unwindClause.getIDExpr(), plan.getLastOperator());
    appendFlattens(unwind->getGroupsPosToFlatten(), plan);
    unwind->setChild(0, plan.getLastOperator());
    unwind->computeFactorizedSchema();
    plan.setLastOperator(unwind);
}

} // namespace planner
} // namespace lbug
