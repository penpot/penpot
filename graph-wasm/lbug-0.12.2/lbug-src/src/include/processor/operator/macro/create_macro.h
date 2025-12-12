#pragma once

#include "catalog/catalog.h"
#include "function/scalar_macro_function.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct CreateMacroInfo {
    std::string macroName;
    std::unique_ptr<function::ScalarMacroFunction> macro;

    CreateMacroInfo(std::string macroName, std::unique_ptr<function::ScalarMacroFunction> macro)
        : macroName{std::move(macroName)}, macro{std::move(macro)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(CreateMacroInfo);

private:
    CreateMacroInfo(const CreateMacroInfo& other)
        : macroName{other.macroName}, macro{other.macro->copy()} {}
};

struct CreateMacroPrintInfo final : OPPrintInfo {
    std::string macroName;

    explicit CreateMacroPrintInfo(std::string macroName) : macroName{std::move(macroName)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<CreateMacroPrintInfo>(new CreateMacroPrintInfo(*this));
    }

private:
    CreateMacroPrintInfo(const CreateMacroPrintInfo& other)
        : OPPrintInfo{other}, macroName{other.macroName} {}
};

class CreateMacro final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::CREATE_MACRO;

public:
    CreateMacro(CreateMacroInfo info, std::shared_ptr<FactorizedTable> messageTable,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          info{std::move(info)} {}

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<CreateMacro>(info.copy(), messageTable, id, printInfo->copy());
    }

private:
    CreateMacroInfo info;
};

} // namespace processor
} // namespace lbug
