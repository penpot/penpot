#pragma once

#include "insert_executor.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct InsertPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;
    common::ConflictAction action;

    InsertPrintInfo(binder::expression_vector expressions, common::ConflictAction action)
        : expressions(std::move(expressions)), action(action) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<InsertPrintInfo>(new InsertPrintInfo(*this));
    }

private:
    InsertPrintInfo(const InsertPrintInfo& other)
        : OPPrintInfo(other), expressions(other.expressions), action(other.action) {}
};

class Insert final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::INSERT;

public:
    Insert(std::vector<NodeInsertExecutor> nodeExecutors,
        std::vector<RelInsertExecutor> relExecutors, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          nodeExecutors{std::move(nodeExecutors)}, relExecutors{std::move(relExecutors)} {}

    bool isParallel() const override { return false; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<Insert>(copyVector(nodeExecutors), copyVector(relExecutors),
            children[0]->copy(), id, printInfo->copy());
    }

private:
    std::vector<NodeInsertExecutor> nodeExecutors;
    std::vector<RelInsertExecutor> relExecutors;
};
} // namespace processor
} // namespace lbug
