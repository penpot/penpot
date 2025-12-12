#include "binder/expression/property_expression.h"
#include "binder/visitor/property_collector.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

// Note: we cannot append ResultCollector for plans enumerated for single query before there could
// be a UNION on top which requires further flatten. So we delay ResultCollector appending to
// enumerate regular query level.
LogicalPlan Planner::planSingleQuery(const NormalizedSingleQuery& singleQuery) {
    auto propertyCollector = PropertyCollector();
    propertyCollector.visitSingleQuery(singleQuery);
    auto properties = propertyCollector.getProperties();
    for (auto& expr : propertyCollector.getProperties()) {
        auto& property = expr->constCast<PropertyExpression>();
        propertyExprCollection.addProperties(property.getVariableName(), expr);
    }
    context.resetState();
    auto plan = LogicalPlan();
    for (auto i = 0u; i < singleQuery.getNumQueryParts(); ++i) {
        planQueryPart(*singleQuery.getQueryPart(i), plan);
    }
    return plan;
}

void Planner::planQueryPart(const NormalizedQueryPart& queryPart, LogicalPlan& plan) {
    // plan read
    for (auto i = 0u; i < queryPart.getNumReadingClause(); i++) {
        planReadingClause(*queryPart.getReadingClause(i), plan);
    }
    // plan update
    for (auto i = 0u; i < queryPart.getNumUpdatingClause(); ++i) {
        planUpdatingClause(*queryPart.getUpdatingClause(i), plan);
    }
    // plan projection
    if (queryPart.hasProjectionBody()) {
        planProjectionBody(queryPart.getProjectionBody(), plan);
        if (queryPart.hasProjectionBodyPredicate()) {
            appendFilter(queryPart.getProjectionBodyPredicate(), plan);
        }
    }
}

} // namespace planner
} // namespace lbug
