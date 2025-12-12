#pragma once

#include "extension/extension_action.h"
#include "logical_simple.h"

namespace lbug {
namespace planner {

class LogicalExtension final : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::EXTENSION;

public:
    explicit LogicalExtension(std::unique_ptr<extension::ExtensionAuxInfo> auxInfo)
        : LogicalSimple{type_}, auxInfo{std::move(auxInfo)} {}

    std::string getExpressionsForPrinting() const override { return path; }

    const extension::ExtensionAuxInfo& getAuxInfo() const { return *auxInfo; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalExtension>(auxInfo->copy());
    }

private:
    std::unique_ptr<extension::ExtensionAuxInfo> auxInfo;
    std::string path;
};

} // namespace planner
} // namespace lbug
