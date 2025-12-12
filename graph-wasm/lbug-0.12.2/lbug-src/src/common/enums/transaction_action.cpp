#include "transaction/transaction_action.h"

#include "common/assert.h"

namespace lbug {
namespace transaction {

std::string TransactionActionUtils::toString(TransactionAction action) {
    switch (action) {
    case TransactionAction::BEGIN_READ: {
        return "BEGIN_READ";
    }
    case TransactionAction::BEGIN_WRITE: {
        return "BEGIN_WRITE";
    }
    case TransactionAction::COMMIT: {
        return "COMMIT";
    }
    case TransactionAction::ROLLBACK: {
        return "ROLLBACK";
    }
    case TransactionAction::CHECKPOINT: {
        return "CHECKPOINT";
    }
    default:
        KU_UNREACHABLE;
    }
}

} // namespace transaction
} // namespace lbug
