#pragma once

#include "bound_statement.h"
#include "transaction/transaction_action.h"

namespace lbug {
namespace binder {

class BoundTransactionStatement final : public BoundStatement {
    static constexpr common::StatementType statementType_ = common::StatementType::TRANSACTION;

public:
    explicit BoundTransactionStatement(transaction::TransactionAction transactionAction)
        : BoundStatement{statementType_, BoundStatementResult::createEmptyResult()},
          transactionAction{transactionAction} {}

    transaction::TransactionAction getTransactionAction() const { return transactionAction; }

private:
    transaction::TransactionAction transactionAction;
};

} // namespace binder
} // namespace lbug
