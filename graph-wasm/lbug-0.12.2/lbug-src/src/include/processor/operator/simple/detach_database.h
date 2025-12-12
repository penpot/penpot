#pragma once

#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct DetatchDatabasePrintInfo final : OPPrintInfo {
    std::string name;

    explicit DetatchDatabasePrintInfo(std::string name) : name{std::move(name)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<DetatchDatabasePrintInfo>(new DetatchDatabasePrintInfo(*this));
    }

private:
    DetatchDatabasePrintInfo(const DetatchDatabasePrintInfo& other)
        : OPPrintInfo{other}, name{other.name} {}
};

class DetachDatabase final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DETACH_DATABASE;

public:
    DetachDatabase(std::string dbName, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          dbName{std::move(dbName)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<DetachDatabase>(dbName, messageTable, id, printInfo->copy());
    }

private:
    std::string dbName;
};

} // namespace processor
} // namespace lbug
