#pragma once

#include <memory>

#include "common/enums/explain_type.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class ExplainStatement : public Statement {
public:
    ExplainStatement(std::unique_ptr<Statement> statementToExplain, common::ExplainType explainType)
        : Statement{common::StatementType::EXPLAIN},
          statementToExplain{std::move(statementToExplain)}, explainType{explainType} {}

    inline Statement* getStatementToExplain() const { return statementToExplain.get(); }

    inline common::ExplainType getExplainType() const { return explainType; }

private:
    std::unique_ptr<Statement> statementToExplain;
    common::ExplainType explainType;
};

} // namespace parser
} // namespace lbug
