#include "transaction/transaction_context.h"

#include "common/exception/transaction_manager.h"
#include "main/client_context.h"
#include "main/database.h"
#include "transaction/transaction_manager.h"

using namespace lbug::common;

namespace lbug {
namespace transaction {

TransactionContext::TransactionContext(main::ClientContext& clientContext)
    : clientContext{clientContext}, mode{TransactionMode::AUTO}, activeTransaction{nullptr} {}

TransactionContext::~TransactionContext() = default;

void TransactionContext::beginReadTransaction() {
    std::unique_lock lck{mtx};
    mode = TransactionMode::MANUAL;
    beginTransactionInternal(TransactionType::READ_ONLY);
}

void TransactionContext::beginWriteTransaction() {
    std::unique_lock lck{mtx};
    mode = TransactionMode::MANUAL;
    beginTransactionInternal(TransactionType::WRITE);
}

void TransactionContext::beginAutoTransaction(bool readOnlyStatement) {
    // LCOV_EXCL_START
    if (hasActiveTransaction()) {
        throw TransactionManagerException(
            "Cannot start a new transaction while there is an active transaction.");
    }
    // LCOV_EXCL_STOP
    beginTransactionInternal(
        readOnlyStatement ? TransactionType::READ_ONLY : TransactionType::WRITE);
}

void TransactionContext::beginRecoveryTransaction() {
    std::unique_lock lck{mtx};
    mode = TransactionMode::MANUAL;
    beginTransactionInternal(TransactionType::RECOVERY);
}

void TransactionContext::validateManualTransaction(bool readOnlyStatement) const {
    KU_ASSERT(hasActiveTransaction());
    if (activeTransaction->isReadOnly() && !readOnlyStatement) {
        throw TransactionManagerException(
            "Can not execute a write query inside a read-only transaction.");
    }
}

void TransactionContext::commit() {
    if (!hasActiveTransaction()) {
        return;
    }
    clientContext.getDatabase()->getTransactionManager()->commit(clientContext, activeTransaction);
    clearTransaction();
}

void TransactionContext::rollback() {
    if (!hasActiveTransaction()) {
        return;
    }
    clientContext.getDatabase()->getTransactionManager()->rollback(clientContext,
        activeTransaction);
    clearTransaction();
}

void TransactionContext::clearTransaction() {
    activeTransaction = nullptr;
    mode = TransactionMode::AUTO;
}

TransactionContext* TransactionContext::Get(const main::ClientContext& context) {
    return context.transactionContext.get();
}

void TransactionContext::beginTransactionInternal(TransactionType transactionType) {
    KU_ASSERT(!activeTransaction);
    activeTransaction = clientContext.getDatabase()->getTransactionManager()->beginTransaction(
        clientContext, transactionType);
}

} // namespace transaction
} // namespace lbug
