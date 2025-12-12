#pragma once

#include "planner/operator/logical_plan.h"

namespace lbug {
namespace planner {

class LogicalPlanUtil {
public:
    static std::string encodeJoin(LogicalPlan& logicalPlan);

private:
    static std::string encode(LogicalOperator* logicalOperator);
    static void encodeRecursive(LogicalOperator* logicalOperator, std::string& encodeString);
    // Encode joins
    static void encodeCrossProduct(LogicalOperator* logicalOperator, std::string& encodeString);
    static void encodeIntersect(LogicalOperator* logicalOperator, std::string& encodeString);
    static void encodeHashJoin(LogicalOperator* logicalOperator, std::string& encodeString);
    static void encodeExtend(LogicalOperator* logicalOperator, std::string& encodeString);
    static void encodeScanNodeTable(LogicalOperator* logicalOperator, std::string& encodeString);
    // Encode filter
    static void encodeFilter(LogicalOperator* logicalOperator, std::string& encodedString);
};

} // namespace planner
} // namespace lbug
