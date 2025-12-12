#include "planner/operator/logical_partitioner.h"

#include "binder/expression/expression_util.h"
#include "common/exception/runtime.h"

namespace lbug {
namespace planner {

static void validateSingleGroup(const Schema& schema) {
    if (schema.getNumGroups() != 1) {
        throw common::RuntimeException(
            "Try to partition multiple factorization group. This should not happen.");
    }
}

void LogicalPartitioner::computeFactorizedSchema() {
    copyChildSchema(0);
    // LCOV_EXCL_START
    validateSingleGroup(*schema);
    // LCOV_EXCL_STOP
    schema->insertToGroupAndScope(info.offset, 0);
}

void LogicalPartitioner::computeFlatSchema() {
    copyChildSchema(0);
    // LCOV_EXCL_START
    validateSingleGroup(*schema);
    // LCOV_EXCL_STOP
    schema->insertToGroupAndScope(info.offset, 0);
}

std::string LogicalPartitioner::getExpressionsForPrinting() const {
    binder::expression_vector expressions;
    for (auto& partitioningInfo : info.partitioningInfos) {
        expressions.push_back(copyFromInfo.columnExprs[partitioningInfo.keyIdx]);
    }
    return binder::ExpressionUtil::toString(expressions);
}

} // namespace planner
} // namespace lbug
