#pragma once

#include "logical_operator.h"

namespace lbug {
namespace planner {

class LogicalEmptyResult final : public LogicalOperator {
public:
    explicit LogicalEmptyResult(const Schema& schema)
        : LogicalOperator{LogicalOperatorType::EMPTY_RESULT}, originalSchema{schema.copy()} {
        this->schema = schema.copy();
    }

    void computeFactorizedSchema() override { schema = originalSchema->copy(); }
    void computeFlatSchema() override {
        createEmptySchema();
        schema->createGroup();
        for (auto& e : originalSchema->getExpressionsInScope()) {
            schema->insertToGroupAndScope(e, 0);
        }
    }

    std::string getExpressionsForPrinting() const override { return std::string{}; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalEmptyResult>(*originalSchema);
    }

private:
    // The original schema of the plan that generates empty result.
    std::unique_ptr<Schema> originalSchema;
};

} // namespace planner
} // namespace lbug
