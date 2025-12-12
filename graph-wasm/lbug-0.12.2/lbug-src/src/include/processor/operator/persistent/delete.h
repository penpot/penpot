#pragma once

#include "delete_executor.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct DeleteNodePrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;
    common::DeleteNodeType deleteType;

    DeleteNodePrintInfo(binder::expression_vector expressions, common::DeleteNodeType deleteType)
        : expressions{std::move(expressions)}, deleteType{deleteType} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<DeleteNodePrintInfo>(new DeleteNodePrintInfo(*this));
    }

private:
    DeleteNodePrintInfo(const DeleteNodePrintInfo& other)
        : OPPrintInfo{other}, expressions{other.expressions}, deleteType{other.deleteType} {}
};

class DeleteNode final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DELETE_;

public:
    DeleteNode(std::vector<std::unique_ptr<NodeDeleteExecutor>> executors,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          executors{std::move(executors)} {}

    bool isParallel() const override { return false; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<DeleteNode>(copyVector(executors), children[0]->copy(), id,
            printInfo->copy());
    }

private:
    std::vector<std::unique_ptr<NodeDeleteExecutor>> executors;
};

struct DeleteRelPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;

    explicit DeleteRelPrintInfo(binder::expression_vector expressions)
        : expressions{std::move(expressions)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<DeleteRelPrintInfo>(new DeleteRelPrintInfo(*this));
    }

private:
    DeleteRelPrintInfo(const DeleteRelPrintInfo& other)
        : OPPrintInfo{other}, expressions{other.expressions} {}
};

class DeleteRel final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DELETE_;

public:
    DeleteRel(std::vector<std::unique_ptr<RelDeleteExecutor>> executors,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          executors{std::move(executors)} {}

    bool isParallel() const override { return false; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<DeleteRel>(copyVector(executors), children[0]->copy(), id,
            printInfo->copy());
    }

private:
    std::vector<std::unique_ptr<RelDeleteExecutor>> executors;
};

} // namespace processor
} // namespace lbug
