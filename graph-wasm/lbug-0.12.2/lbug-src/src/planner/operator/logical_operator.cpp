#include "planner/operator/logical_operator.h"

#include "common/exception/runtime.h"

using namespace lbug::common;

namespace lbug {
namespace planner {

// LCOV_EXCL_START
std::string LogicalOperatorUtils::logicalOperatorTypeToString(LogicalOperatorType type) {
    switch (type) {
    case LogicalOperatorType::ACCUMULATE:
        return "ACCUMULATE";
    case LogicalOperatorType::AGGREGATE:
        return "AGGREGATE";
    case LogicalOperatorType::ALTER:
        return "ALTER";
    case LogicalOperatorType::ATTACH_DATABASE:
        return "ATTACH_DATABASE";
    case LogicalOperatorType::COPY_FROM:
        return "COPY_FROM";
    case LogicalOperatorType::COPY_TO:
        return "COPY_TO";
    case LogicalOperatorType::CREATE_MACRO:
        return "CREATE_MACRO";
    case LogicalOperatorType::CREATE_SEQUENCE:
        return "CREATE_SEQUENCE";
    case LogicalOperatorType::CREATE_TABLE:
        return "CREATE_TABLE";
    case LogicalOperatorType::CROSS_PRODUCT:
        return "CROSS_PRODUCT";
    case LogicalOperatorType::DELETE:
        return "DELETE_NODE";
    case LogicalOperatorType::DETACH_DATABASE:
        return "DETACH_DATABASE";
    case LogicalOperatorType::DISTINCT:
        return "DISTINCT";
    case LogicalOperatorType::DROP:
        return "DROP";
    case LogicalOperatorType::DUMMY_SCAN:
        return "DUMMY_SCAN";
    case LogicalOperatorType::DUMMY_SINK:
        return "DUMMY_SINK";
    case LogicalOperatorType::EMPTY_RESULT:
        return "EMPTY_RESULT";
    case LogicalOperatorType::EXPLAIN:
        return "EXPLAIN";
    case LogicalOperatorType::EXPRESSIONS_SCAN:
        return "EXPRESSIONS_SCAN";
    case LogicalOperatorType::EXTENSION:
        return "LOAD";
    case LogicalOperatorType::EXPORT_DATABASE:
        return "EXPORT_DATABASE";
    case LogicalOperatorType::EXTEND:
        return "EXTEND";
    case LogicalOperatorType::FILTER:
        return "FILTER";
    case LogicalOperatorType::FLATTEN:
        return "FLATTEN";
    case LogicalOperatorType::HASH_JOIN:
        return "HASH_JOIN";
    case LogicalOperatorType::IMPORT_DATABASE:
        return "IMPORT_DATABASE";
    case LogicalOperatorType::INDEX_LOOK_UP:
        return "INDEX_LOOK_UP";
    case LogicalOperatorType::INTERSECT:
        return "INTERSECT";
    case LogicalOperatorType::INSERT:
        return "INSERT";
    case LogicalOperatorType::LIMIT:
        return "LIMIT";
    case LogicalOperatorType::MERGE:
        return "MERGE";
    case LogicalOperatorType::MULTIPLICITY_REDUCER:
        return "MULTIPLICITY_REDUCER";
    case LogicalOperatorType::NODE_LABEL_FILTER:
        return "NODE_LABEL_FILTER";
    case LogicalOperatorType::NOOP:
        return "NOOP";
    case LogicalOperatorType::ORDER_BY:
        return "ORDER_BY";
    case LogicalOperatorType::PARTITIONER:
        return "PARTITIONER";
    case LogicalOperatorType::PATH_PROPERTY_PROBE:
        return "PATH_PROPERTY_PROBE";
    case LogicalOperatorType::PROJECTION:
        return "PROJECTION";
    case LogicalOperatorType::RECURSIVE_EXTEND:
        return "RECURSIVE_EXTEND";
    case LogicalOperatorType::SCAN_NODE_TABLE:
        return "SCAN_NODE_TABLE";
    case LogicalOperatorType::SEMI_MASKER:
        return "SEMI_MASKER";
    case LogicalOperatorType::SET_PROPERTY:
        return "SET_PROPERTY";
    case LogicalOperatorType::STANDALONE_CALL:
        return "STANDALONE_CALL";
    case LogicalOperatorType::TABLE_FUNCTION_CALL:
        return "TABLE_FUNCTION_CALL";
    case LogicalOperatorType::TRANSACTION:
        return "TRANSACTION";
    case LogicalOperatorType::UNION_ALL:
        return "UNION_ALL";
    case LogicalOperatorType::UNWIND:
        return "UNWIND";
    case LogicalOperatorType::USE_DATABASE:
        return "USE_DATABASE";
    case LogicalOperatorType::CREATE_TYPE:
        return "CREATE_TYPE";
    case LogicalOperatorType::EXTENSION_CLAUSE:
        return "EXTENSION_CLAUSE";
    default:
        throw RuntimeException("Unknown logical operator type.");
    }
}
// LCOV_EXCL_STOP

bool LogicalOperatorUtils::isUpdate(LogicalOperatorType type) {
    switch (type) {
    case LogicalOperatorType::INSERT:
    case LogicalOperatorType::DELETE:
    case LogicalOperatorType::SET_PROPERTY:
    case LogicalOperatorType::MERGE:
        return true;
    default:
        return false;
    }
}

bool LogicalOperatorUtils::isAccHashJoin(const LogicalOperator& op) {
    return op.getOperatorType() == LogicalOperatorType::HASH_JOIN &&
           op.getChild(0)->getOperatorType() == LogicalOperatorType::ACCUMULATE;
}

LogicalOperator::LogicalOperator(LogicalOperatorType operatorType,
    std::shared_ptr<LogicalOperator> child, std::optional<common::cardinality_t> cardinality)
    : operatorType{operatorType},
      cardinality{cardinality.has_value() ? cardinality.value() : child->getCardinality()} {
    children.push_back(std::move(child));
}

LogicalOperator::LogicalOperator(LogicalOperatorType operatorType,
    std::shared_ptr<LogicalOperator> left, std::shared_ptr<LogicalOperator> right)
    : LogicalOperator{operatorType} {
    children.push_back(std::move(left));
    children.push_back(std::move(right));
}

LogicalOperator::LogicalOperator(LogicalOperatorType operatorType,
    const logical_op_vector_t& children)
    : LogicalOperator{operatorType} {
    for (auto& child : children) {
        this->children.push_back(child);
    }
}

bool LogicalOperator::hasUpdateRecursive() {
    if (LogicalOperatorUtils::isUpdate(operatorType)) {
        return true;
    }
    for (auto& child : children) {
        if (child->hasUpdateRecursive()) {
            return true;
        }
    }
    return false;
}

std::string LogicalOperator::toString(uint64_t depth) const {
    auto padding = std::string(depth * 4, ' ');
    std::string result = padding;
    result += LogicalOperatorUtils::logicalOperatorTypeToString(operatorType) + "[" +
              getExpressionsForPrinting() + "]";
    if (children.size() == 1) {
        result += "\n" + children[0]->toString(depth);
    } else {
        for (auto& child : children) {
            result += "\n" + padding + "CHILD:\n" + child->toString(depth + 1);
        }
    }
    return result;
}

logical_op_vector_t LogicalOperator::copy(const logical_op_vector_t& ops) {
    logical_op_vector_t result;
    result.reserve(ops.size());
    for (auto& op : ops) {
        result.push_back(op->copy());
    }
    return result;
}

} // namespace planner
} // namespace lbug
