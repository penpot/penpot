#include "planner/operator/scan/logical_dummy_scan.h"

#include "binder/expression/literal_expression.h"
#include "common/constants.h"

using namespace lbug::common;

namespace lbug {
namespace planner {

void LogicalDummyScan::computeFactorizedSchema() {
    createEmptySchema();
    schema->createGroup();
}

void LogicalDummyScan::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
}

std::shared_ptr<binder::Expression> LogicalDummyScan::getDummyExpression() {
    return std::make_shared<binder::LiteralExpression>(
        Value::createNullValue(LogicalType::STRING()), InternalKeyword::PLACE_HOLDER);
}

} // namespace planner
} // namespace lbug
