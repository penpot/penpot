#pragma once

#include <string>

#include "parser/statement.h"

namespace lbug {
namespace parser {

class DatabaseStatement : public Statement {
public:
    explicit DatabaseStatement(common::StatementType type, std::string dbName)
        : Statement{type}, dbName{std::move(dbName)} {}

    std::string getDBName() const { return dbName; }

private:
    std::string dbName;
};

} // namespace parser
} // namespace lbug
