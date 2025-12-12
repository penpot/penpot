#pragma once

#include "extension_print_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct UninstallExtensionPrintInfo final : public ExtensionPrintInfo {
    explicit UninstallExtensionPrintInfo(std::string extensionName)
        : ExtensionPrintInfo{std::move(extensionName)} {}

    std::string toString() const override { return "Uninstall " + extensionName; }

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::make_unique<UninstallExtensionPrintInfo>(*this);
    }
};

class UninstallExtension final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::UNINSTALL_EXTENSION;

public:
    UninstallExtension(std::string path, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          path{std::move(path)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<UninstallExtension>(path, messageTable, id, printInfo->copy());
    }

private:
    std::string path;
};

} // namespace processor
} // namespace lbug
