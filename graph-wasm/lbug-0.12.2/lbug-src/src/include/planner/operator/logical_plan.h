#pragma once

#include "logical_operator.h"

namespace lbug {
namespace planner {

using cardinality_t = uint64_t;

class LBUG_API LogicalPlan {
    friend class CardinalityEstimator;
    friend class CostModel;

public:
    LogicalPlan() : cost{0} {}
    LogicalPlan(const LogicalPlan& other) : lastOperator{other.lastOperator}, cost{other.cost} {}
    EXPLICIT_COPY_DEFAULT_MOVE(LogicalPlan);

    void setLastOperator(std::shared_ptr<LogicalOperator> op) { lastOperator = std::move(op); }

    bool isEmpty() const { return lastOperator == nullptr; }

    std::shared_ptr<LogicalOperator> getLastOperator() const { return lastOperator; }
    LogicalOperator& getLastOperatorRef() const {
        KU_ASSERT(lastOperator);
        return *lastOperator;
    }
    Schema* getSchema() const { return lastOperator->getSchema(); }

    cardinality_t getCardinality() const {
        KU_ASSERT(lastOperator);
        return lastOperator->getCardinality();
    }

    void setCost(uint64_t cost_) { cost = cost_; }
    uint64_t getCost() const { return cost; }

    std::string toString() const { return lastOperator->toString(); }

    bool isProfile() const;
    bool hasUpdate() const;

private:
    std::shared_ptr<LogicalOperator> lastOperator;
    uint64_t cost;
};

} // namespace planner
} // namespace lbug
