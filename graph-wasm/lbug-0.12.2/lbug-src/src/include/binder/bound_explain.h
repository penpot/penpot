#pragma once

#include "binder/bound_statement.h"
#include "common/enums/explain_type.h"

namespace lbug {
namespace binder {

class BoundExplain final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::EXPLAIN;

public:
    explicit BoundExplain(std::unique_ptr<BoundStatement> statementToExplain,
        common::ExplainType explainType)
        : BoundStatement{type_, BoundStatementResult::createSingleStringColumnResult(
                                    "explain result" /* columnName */)},
          statementToExplain{std::move(statementToExplain)}, explainType{explainType} {}

    BoundStatement* getStatementToExplain() const { return statementToExplain.get(); }

    common::ExplainType getExplainType() const { return explainType; }

private:
    std::unique_ptr<BoundStatement> statementToExplain;
    common::ExplainType explainType;
};

} // namespace binder
} // namespace lbug
