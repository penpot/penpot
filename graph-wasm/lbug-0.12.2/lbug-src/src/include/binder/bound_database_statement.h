#pragma once

#include "binder/bound_statement.h"

namespace lbug {
namespace binder {

class BoundDatabaseStatement : public BoundStatement {
public:
    explicit BoundDatabaseStatement(common::StatementType statementType, std::string dbName)
        : BoundStatement{statementType, BoundStatementResult::createSingleStringColumnResult()},
          dbName{std::move(dbName)} {}

    std::string getDBName() const { return dbName; }

private:
    std::string dbName;
};

} // namespace binder
} // namespace lbug
