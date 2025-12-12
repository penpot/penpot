#pragma once

#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct UseDatabasePrintInfo final : OPPrintInfo {
    std::string dbName;

    explicit UseDatabasePrintInfo(std::string dbName) : dbName(std::move(dbName)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<UseDatabasePrintInfo>(new UseDatabasePrintInfo(*this));
    }

private:
    UseDatabasePrintInfo(const UseDatabasePrintInfo& other)
        : OPPrintInfo(other), dbName(other.dbName) {}
};

class UseDatabase final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::USE_DATABASE;

public:
    UseDatabase(std::string dbName, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          dbName{std::move(dbName)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<UseDatabase>(dbName, messageTable, id, printInfo->copy());
    }

private:
    std::string dbName;
};

} // namespace processor
} // namespace lbug
