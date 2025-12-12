#pragma once

#include "binder/bound_attach_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct AttachDatabasePrintInfo final : OPPrintInfo {
    std::string dbName;
    std::string dbPath;

    AttachDatabasePrintInfo(std::string dbName, std::string dbPath)
        : dbName{std::move(dbName)}, dbPath{std::move(dbPath)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<AttachDatabasePrintInfo>(new AttachDatabasePrintInfo(*this));
    }

private:
    AttachDatabasePrintInfo(const AttachDatabasePrintInfo& other)
        : OPPrintInfo{other}, dbName{other.dbName}, dbPath{other.dbPath} {}
};

class AttachDatabase final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::ATTACH_DATABASE;

public:
    AttachDatabase(binder::AttachInfo attachInfo, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          attachInfo{std::move(attachInfo)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<AttachDatabase>(attachInfo, messageTable, id, printInfo->copy());
    }

private:
    binder::AttachInfo attachInfo;
};

} // namespace processor
} // namespace lbug
