#pragma once

#include "common/exception/internal.h"
#include "common/metric.h"
#include "processor/operator/physical_operator.h"
#include "processor/result/factorized_table.h"
#include "processor/result/result_set_descriptor.h"

namespace lbug {
namespace main {
class QueryResult;
}
namespace processor {

class LBUG_API Sink : public PhysicalOperator {
public:
    Sink(PhysicalOperatorType operatorType, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{operatorType, id, std::move(printInfo)} {}
    Sink(PhysicalOperatorType operatorType, std::unique_ptr<PhysicalOperator> child,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{operatorType, std::move(child), id, std::move(printInfo)} {}

    bool isSink() const override { return true; }

    void setDescriptor(std::unique_ptr<ResultSetDescriptor> descriptor) {
        KU_ASSERT(resultSetDescriptor == nullptr);
        resultSetDescriptor = std::move(descriptor);
    }
    std::unique_ptr<ResultSet> getResultSet(storage::MemoryManager* memoryManager);

    void execute(ResultSet* resultSet, ExecutionContext* context) {
        initLocalState(resultSet, context);
        metrics->executionTime.start();
        executeInternal(context);
        metrics->executionTime.stop();
    }

    virtual std::unique_ptr<main::QueryResult> getQueryResult() const {
        throw common::InternalException(
            common::stringFormat("{} operator does not implement getQueryResult.",
                PhysicalOperatorUtils::operatorTypeToString(operatorType)));
    }

    virtual std::shared_ptr<FactorizedTable> getResultFTable() const {
        throw common::InternalException(common::stringFormat(
            "Trying to get result table from {} operator which doesn't have one.",
            PhysicalOperatorUtils::operatorTypeToString(operatorType)));
    }

    virtual bool terminate() const { return false; }

    std::unique_ptr<PhysicalOperator> copy() override = 0;

protected:
    virtual void executeInternal(ExecutionContext* context) = 0;

    bool getNextTuplesInternal(ExecutionContext* /*context*/) final {
        throw common::InternalException(
            "getNextTupleInternal() should not be called on sink operator.");
    }

protected:
    std::unique_ptr<ResultSetDescriptor> resultSetDescriptor;
};

class LBUG_API DummySink final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DUMMY_SINK;

public:
    DummySink(std::unique_ptr<PhysicalOperator> child, uint32_t id)
        : Sink{type_, std::move(child), id, OPPrintInfo::EmptyInfo()} {}

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<DummySink>(children[0]->copy(), id);
    }

protected:
    void executeInternal(ExecutionContext* context) override {
        while (children[0]->getNextTuple(context)) {
            // DO NOTHING.
        }
    }
};

class SimpleSink : public Sink {
public:
    SimpleSink(PhysicalOperatorType operatorType, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{operatorType, id, std::move(printInfo)}, messageTable{std::move(messageTable)} {}

    bool isSource() const final { return true; }
    bool isParallel() const final { return false; }

    std::unique_ptr<main::QueryResult> getQueryResult() const override;

    std::shared_ptr<FactorizedTable> getResultFTable() const override { return messageTable; }

protected:
    void appendMessage(const std::string& msg, storage::MemoryManager* memoryManager);

protected:
    std::shared_ptr<FactorizedTable> messageTable;
};

// For cases like Export. We need a parent for ExportDB and multiple CopyTo. This parent does not
// have any logic other than propagating the result fTable.
class DummySimpleSink final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DUMMY_SIMPLE_SINK;

public:
    DummySimpleSink(std::shared_ptr<FactorizedTable> messageTable, physical_op_id id)
        : SimpleSink{type_, std::move(messageTable), id, OPPrintInfo::EmptyInfo()} {}

    void executeInternal(ExecutionContext*) override {}

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<DummySimpleSink>(messageTable, id);
    }
};

} // namespace processor
} // namespace lbug
