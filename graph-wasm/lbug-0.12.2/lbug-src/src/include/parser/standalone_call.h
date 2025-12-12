#pragma once

#include "parser/expression/parsed_expression.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class StandaloneCall : public Statement {
public:
    explicit StandaloneCall(std::string optionName, std::unique_ptr<ParsedExpression> optionValue)
        : Statement{common::StatementType::STANDALONE_CALL}, optionName{std::move(optionName)},
          optionValue{std::move(optionValue)} {}

    std::string getOptionName() const { return optionName; }

    ParsedExpression* getOptionValue() const { return optionValue.get(); }

private:
    std::string optionName;
    std::unique_ptr<ParsedExpression> optionValue;
};

} // namespace parser
} // namespace lbug
