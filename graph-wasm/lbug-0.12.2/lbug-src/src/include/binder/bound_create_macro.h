#pragma once

#include "binder/bound_statement.h"
#include "function/scalar_macro_function.h"

namespace lbug {
namespace binder {

class BoundCreateMacro final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_MACRO;

public:
    explicit BoundCreateMacro(std::string macroName,
        std::unique_ptr<function::ScalarMacroFunction> macro)
        : BoundStatement{type_,
              BoundStatementResult::createSingleStringColumnResult("result" /* columnName */)},
          macroName{std::move(macroName)}, macro{std::move(macro)} {}

    std::string getMacroName() const { return macroName; }

    std::unique_ptr<function::ScalarMacroFunction> getMacro() const { return macro->copy(); }

private:
    std::string macroName;
    std::unique_ptr<function::ScalarMacroFunction> macro;
};

} // namespace binder
} // namespace lbug
