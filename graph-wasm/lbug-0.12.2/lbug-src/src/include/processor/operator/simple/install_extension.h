#pragma once

#include "extension/extension_installer.h"
#include "extension_print_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct InstallExtensionPrintInfo final : public ExtensionPrintInfo {
    explicit InstallExtensionPrintInfo(std::string extensionName)
        : ExtensionPrintInfo{std::move(extensionName)} {}

    std::string toString() const override { return "Install " + extensionName; }

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::make_unique<InstallExtensionPrintInfo>(*this);
    }
};

class InstallExtension final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::INSTALL_EXTENSION;

public:
    InstallExtension(extension::InstallExtensionInfo info,
        std::shared_ptr<FactorizedTable> messageTable, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          info{std::move(info)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<InstallExtension>(info, messageTable, id, printInfo->copy());
    }

private:
    void setOutputMessage(bool installed, storage::MemoryManager* memoryManager);

private:
    extension::InstallExtensionInfo info;
};

} // namespace processor
} // namespace lbug
