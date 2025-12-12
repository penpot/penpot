#include "processor/operator/transaction.h"

#include "common/exception/transaction_manager.h"
#include "processor/execution_context.h"
#include "transaction/transaction_context.h"
#include "transaction/transaction_manager.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

std::string TransactionPrintInfo::toString() const {
    std::string result = "Action: ";
    result += TransactionActionUtils::toString(action);
    return result;
}

bool Transaction::getNextTuplesInternal(ExecutionContext* context) {
    if (hasExecuted) {
        return false;
    }
    hasExecuted = true;
    auto clientContext = context->clientContext;
    auto transactionContext = TransactionContext::Get(*clientContext);
    validateActiveTransaction(*transactionContext);
    switch (transactionAction) {
    case TransactionAction::BEGIN_READ: {
        transactionContext->beginReadTransaction();
    } break;
    case TransactionAction::BEGIN_WRITE: {
        transactionContext->beginWriteTransaction();
    } break;
    case TransactionAction::COMMIT: {
        transactionContext->commit();
    } break;
    case TransactionAction::ROLLBACK: {
        transactionContext->rollback();
    } break;
    case TransactionAction::CHECKPOINT: {
        TransactionManager::Get(*clientContext)->checkpoint(*clientContext);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    return true;
}

void Transaction::validateActiveTransaction(const TransactionContext& context) const {
    switch (transactionAction) {
    case TransactionAction::BEGIN_READ:
    case TransactionAction::BEGIN_WRITE: {
        if (context.hasActiveTransaction()) {
            throw TransactionManagerException(
                "Connection already has an active transaction. Cannot start a transaction within "
                "another one. For concurrent multiple transactions, please open other "
                "connections.");
        }
    } break;
    case TransactionAction::COMMIT:
    case TransactionAction::ROLLBACK: {
        if (!context.hasActiveTransaction()) {
            throw TransactionManagerException(stringFormat("No active transaction for {}.",
                TransactionActionUtils::toString(transactionAction)));
        }
    } break;
    case TransactionAction::CHECKPOINT: {
        if (context.hasActiveTransaction()) {
            throw TransactionManagerException(stringFormat("Found active transaction for {}.",
                TransactionActionUtils::toString(transactionAction)));
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

} // namespace processor
} // namespace lbug
