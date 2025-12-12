#pragma once

#include "function/scalar_macro_function.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalCreateMacroPrintInfo final : OPPrintInfo {
    std::string macroName;

    explicit LogicalCreateMacroPrintInfo(std::string macroName) : macroName(std::move(macroName)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<LogicalCreateMacroPrintInfo>(new LogicalCreateMacroPrintInfo(*this));
    }

private:
    LogicalCreateMacroPrintInfo(const LogicalCreateMacroPrintInfo& other)
        : OPPrintInfo(other), macroName(other.macroName) {}
};

class LogicalCreateMacro final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::CREATE_MACRO;

public:
    LogicalCreateMacro(std::string macroName, std::unique_ptr<function::ScalarMacroFunction> macro)
        : LogicalOperator{type_}, macroName{std::move(macroName)}, macro{std::move(macro)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getMacroName() const { return macroName; }

    std::unique_ptr<function::ScalarMacroFunction> getMacro() const { return macro->copy(); }

    std::string getExpressionsForPrinting() const override { return macroName; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalCreateMacro>(macroName, macro->copy());
    }

private:
    std::string macroName;
    std::shared_ptr<function::ScalarMacroFunction> macro;
};

} // namespace planner
} // namespace lbug
