#pragma once

#include "parser/ddl/drop_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct DropPrintInfo final : OPPrintInfo {
    std::string name;

    explicit DropPrintInfo(std::string name) : name{std::move(name)} {}

    std::string toString() const override { return name; }

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<DropPrintInfo>(new DropPrintInfo(*this));
    }

private:
    DropPrintInfo(const DropPrintInfo& other) : OPPrintInfo{other}, name{other.name} {}
};

class Drop final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::DROP;

public:
    Drop(parser::DropInfo dropInfo, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          dropInfo{std::move(dropInfo)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<Drop>(dropInfo, messageTable, id, printInfo->copy());
    }

private:
    void dropSequence(const main::ClientContext* context);
    void dropTable(const main::ClientContext* context);
    void dropMacro(const main::ClientContext* context);
    void handleMacroExistence(const main::ClientContext* context);
    void dropRelGroup(const main::ClientContext* context);

private:
    parser::DropInfo dropInfo;
};

} // namespace processor
} // namespace lbug
