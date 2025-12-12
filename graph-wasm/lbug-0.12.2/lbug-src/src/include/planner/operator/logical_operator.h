#pragma once

#include <optional>

#include "common/cast.h"
#include "planner/operator/operator_print_info.h"
#include "planner/operator/schema.h"

namespace lbug {
namespace planner {

// This ENUM is sorted by alphabetical order.
enum class LogicalOperatorType : uint8_t {
    ACCUMULATE,
    AGGREGATE,
    ALTER,
    ATTACH_DATABASE,
    COPY_FROM,
    COPY_TO,
    CREATE_MACRO,
    CREATE_SEQUENCE,
    CREATE_TABLE,
    CREATE_TYPE,
    CROSS_PRODUCT,
    DELETE,
    DETACH_DATABASE,
    DISTINCT,
    DROP,
    DUMMY_SCAN,
    DUMMY_SINK,
    EMPTY_RESULT,
    EXPLAIN,
    EXPRESSIONS_SCAN,
    EXTEND,
    EXTENSION,
    EXPORT_DATABASE,
    FILTER,
    FLATTEN,
    HASH_JOIN,
    IMPORT_DATABASE,
    INDEX_LOOK_UP,
    INTERSECT,
    INSERT,
    LIMIT,
    MERGE,
    MULTIPLICITY_REDUCER,
    NODE_LABEL_FILTER,
    NOOP,
    ORDER_BY,
    PARTITIONER,
    PATH_PROPERTY_PROBE,
    PROJECTION,
    RECURSIVE_EXTEND,
    SCAN_NODE_TABLE,
    SEMI_MASKER,
    SET_PROPERTY,
    STANDALONE_CALL,
    TABLE_FUNCTION_CALL,
    TRANSACTION,
    UNION_ALL,
    UNWIND,
    USE_DATABASE,
    EXTENSION_CLAUSE,
};

class LogicalOperator;
using logical_op_vector_t = std::vector<std::shared_ptr<LogicalOperator>>;

struct LogicalOperatorUtils {
    static std::string logicalOperatorTypeToString(LogicalOperatorType type);
    static bool isUpdate(LogicalOperatorType type);
    static bool isAccHashJoin(const LogicalOperator& op);
};

class LBUG_API LogicalOperator {
public:
    explicit LogicalOperator(LogicalOperatorType operatorType)
        : operatorType{operatorType}, cardinality{1} {}
    explicit LogicalOperator(LogicalOperatorType operatorType,
        std::shared_ptr<LogicalOperator> child,
        std::optional<common::cardinality_t> cardinality = {});
    explicit LogicalOperator(LogicalOperatorType operatorType,
        std::shared_ptr<LogicalOperator> left, std::shared_ptr<LogicalOperator> right);
    explicit LogicalOperator(LogicalOperatorType operatorType, const logical_op_vector_t& children);

    virtual ~LogicalOperator() = default;

    uint32_t getNumChildren() const { return children.size(); }
    std::shared_ptr<LogicalOperator> getChild(uint64_t idx) const { return children[idx]; }
    std::vector<std::shared_ptr<LogicalOperator>> getChildren() const { return children; }
    void setChild(uint64_t idx, std::shared_ptr<LogicalOperator> child) {
        children[idx] = std::move(child);
    }
    void addChild(std::shared_ptr<LogicalOperator> child) { children.push_back(std::move(child)); }
    void setCardinality(common::cardinality_t cardinality_) { this->cardinality = cardinality_; }

    // Operator type.
    LogicalOperatorType getOperatorType() const { return operatorType; }
    bool hasUpdateRecursive();

    // Schema
    Schema* getSchema() const { return schema.get(); }
    virtual void computeFactorizedSchema() = 0;
    virtual void computeFlatSchema() = 0;

    // Printing.
    virtual std::string getExpressionsForPrinting() const = 0;
    // Print the sub-plan rooted at this operator.
    virtual std::string toString(uint64_t depth = 0) const;

    virtual std::unique_ptr<OPPrintInfo> getPrintInfo() const {
        return std::make_unique<OPPrintInfo>();
    }
    common::cardinality_t getCardinality() const { return cardinality; }

    // TODO: remove this function once planner do not share operator across plans
    virtual std::unique_ptr<LogicalOperator> copy() = 0;
    static logical_op_vector_t copy(const logical_op_vector_t& ops);

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

protected:
    void createEmptySchema() { schema = std::make_unique<Schema>(); }
    void copyChildSchema(uint32_t idx) { schema = children[idx]->getSchema()->copy(); }

protected:
    LogicalOperatorType operatorType;
    std::unique_ptr<Schema> schema;
    logical_op_vector_t children;
    common::cardinality_t cardinality;
};

} // namespace planner
} // namespace lbug
