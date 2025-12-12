#pragma once

#include "parser/expression/parsed_expression.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

using default_macro_args = std::vector<std::pair<std::string, std::unique_ptr<ParsedExpression>>>;

class CreateMacro final : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_MACRO;

public:
    CreateMacro(std::string macroName, std::unique_ptr<ParsedExpression> macroExpression,
        std::vector<std::string> positionalArgs, default_macro_args defaultArgs)
        : Statement{type_}, macroName{std::move(macroName)},
          macroExpression{std::move(macroExpression)}, positionalArgs{std::move(positionalArgs)},
          defaultArgs{std::move(defaultArgs)} {}

    std::string getMacroName() const { return macroName; }

    ParsedExpression* getMacroExpression() const { return macroExpression.get(); }

    std::vector<std::string> getPositionalArgs() const { return positionalArgs; }

    std::vector<std::pair<std::string, ParsedExpression*>> getDefaultArgs() const;

public:
    std::string macroName;
    std::unique_ptr<ParsedExpression> macroExpression;
    std::vector<std::string> positionalArgs;
    default_macro_args defaultArgs;
};

} // namespace parser
} // namespace lbug
