#pragma once

#include "parser/statement.h"

namespace lbug {
namespace extension {

class ExtensionStatement : public parser::Statement {
    static constexpr common::StatementType type_ = common::StatementType::EXTENSION_CLAUSE;

public:
    explicit ExtensionStatement(std::string statementName)
        : parser::Statement{type_}, statementName{std::move(statementName)} {}

    std::string getStatementName() const { return statementName; }

private:
    std::string statementName;
};

} // namespace extension
} // namespace lbug
