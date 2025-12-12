#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalDistinct final : public LogicalOperator {
public:
    LogicalDistinct(binder::expression_vector keys, std::shared_ptr<LogicalOperator> child)
        : LogicalDistinct{LogicalOperatorType::DISTINCT, keys, binder::expression_vector{},
              std::move(child)} {}
    LogicalDistinct(LogicalOperatorType type, binder::expression_vector keys,
        binder::expression_vector payloads, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type, std::move(child)}, keys{std::move(keys)},
          payloads{std::move(payloads)}, skipNum{UINT64_MAX}, limitNum{UINT64_MAX} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    virtual f_group_pos_set getGroupsPosToFlatten();

    std::string getExpressionsForPrinting() const override;

    binder::expression_vector getKeys() const { return keys; }
    void setKeys(binder::expression_vector expressions) { keys = std::move(expressions); }
    binder::expression_vector getPayloads() const { return payloads; }
    void setPayloads(binder::expression_vector expressions) { payloads = std::move(expressions); }

    void setSkipNum(common::offset_t num) { skipNum = num; }
    bool hasSkipNum() const { return skipNum != common::INVALID_LIMIT; }
    common::offset_t getSkipNum() const { return skipNum; }
    void setLimitNum(common::offset_t num) { limitNum = num; }
    bool hasLimitNum() const { return limitNum != common::INVALID_LIMIT; }
    common::offset_t getLimitNum() const { return limitNum; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalDistinct>(operatorType, keys, payloads, children[0]->copy());
    }

protected:
    binder::expression_vector getKeysAndPayloads() const;

protected:
    binder::expression_vector keys;
    // Payloads meaning additional keys that are functional dependent on the keys above.
    binder::expression_vector payloads;

private:
    common::offset_t skipNum;
    common::offset_t limitNum;
};

} // namespace planner
} // namespace lbug
