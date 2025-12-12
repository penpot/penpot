#pragma once

#include "binder/bound_statement.h"

namespace lbug {
namespace extension {

class BoundExtensionClause : public binder::BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::EXTENSION_CLAUSE;

public:
    explicit BoundExtensionClause(std::string statementName)
        : BoundStatement{type_, binder::BoundStatementResult::createSingleStringColumnResult(
                                    "result" /* columnName */)},
          statementName{std::move(statementName)} {}

    std::string getStatementName() const { return statementName; }

private:
    std::string statementName;
};

} // namespace extension
} // namespace lbug
