#pragma once

#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct LoadExtensionPrintInfo final : OPPrintInfo {
    std::string extensionName;

    explicit LoadExtensionPrintInfo(std::string extensionName)
        : extensionName{std::move(extensionName)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LoadExtensionPrintInfo>(new LoadExtensionPrintInfo(*this));
    }

private:
    LoadExtensionPrintInfo(const LoadExtensionPrintInfo& other)
        : OPPrintInfo{other}, extensionName{other.extensionName} {}
};

class LoadExtension final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::LOAD_EXTENSION;

public:
    LoadExtension(std::string path, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          path{std::move(path)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<LoadExtension>(path, messageTable, id, printInfo->copy());
    }

private:
    std::string path;
};

} // namespace processor
} // namespace lbug
