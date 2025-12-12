#include "transaction/transaction_manager.h"

#include <thread>

#include "common/exception/checkpoint.h"
#include "common/exception/transaction_manager.h"
#include "main/attached_database.h"
#include "main/client_context.h"
#include "main/database.h"
#include "main/db_config.h"
#include "storage/checkpointer.h"
#include "storage/wal/local_wal.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace transaction {

Transaction* TransactionManager::beginTransaction(main::ClientContext& clientContext,
    TransactionType type) {
    // We acquire the lock for starting new transactions. In case this cannot be acquired, this
    // ensures calls to other public functions are not restricted.
    std::unique_lock publicFunctionLck{mtxForSerializingPublicFunctionCalls};
    std::unique_lock newTransactionLck{mtxForStartingNewTransactions};
    switch (type) {
    case TransactionType::READ_ONLY: {
        auto transaction =
            std::make_unique<Transaction>(clientContext, type, ++lastTransactionID, lastTimestamp);
        activeTransactions.push_back(std::move(transaction));
        return activeTransactions.back().get();
    }
    case TransactionType::RECOVERY:
    case TransactionType::WRITE: {
        if (!clientContext.getDBConfig()->enableMultiWrites && hasActiveWriteTransactionNoLock()) {
            throw TransactionManagerException(
                "Cannot start a new write transaction in the system. "
                "Only one write transaction at a time is allowed in the system.");
        }
        auto transaction =
            std::make_unique<Transaction>(clientContext, type, ++lastTransactionID, lastTimestamp);
        if (transaction->shouldLogToWAL()) {
            transaction->getLocalWAL().logBeginTransaction();
        }
        activeTransactions.push_back(std::move(transaction));
        return activeTransactions.back().get();
    }
        // LCOV_EXCL_START
    default: {
        throw TransactionManagerException("Invalid transaction type to begin transaction.");
    }
        // LCOV_EXCL_STOP
    }
}

void TransactionManager::commit(main::ClientContext& clientContext, Transaction* transaction) {
    std::unique_lock lck{mtxForSerializingPublicFunctionCalls};
    clientContext.cleanUp();
    switch (transaction->getType()) {
    case TransactionType::READ_ONLY: {
        clearTransactionNoLock(transaction->getID());
    } break;
    case TransactionType::RECOVERY:
    case TransactionType::WRITE: {
        lastTimestamp++;
        transaction->commitTS = lastTimestamp;
        transaction->commit(&wal);
        auto shouldCheckpoint = transaction->shouldForceCheckpoint() ||
                                Checkpointer::canAutoCheckpoint(clientContext, *transaction);
        clearTransactionNoLock(transaction->getID());
        if (shouldCheckpoint) {
            checkpointNoLock(clientContext);
        }
    } break;
        // LCOV_EXCL_START
    default: {
        throw TransactionManagerException("Invalid transaction type to commit.");
    }
        // LCOV_EXCL_STOP
    }
}

// Note: We take in additional `transaction` here is due to that `transactionContext` might be
// destructed when a transaction throws an exception, while we need to roll back the active
// transaction still.
void TransactionManager::rollback(main::ClientContext& clientContext, Transaction* transaction) {
    std::unique_lock lck{mtxForSerializingPublicFunctionCalls};
    clientContext.cleanUp();
    switch (transaction->getType()) {
    case TransactionType::READ_ONLY: {
        clearTransactionNoLock(transaction->getID());
    } break;
    case TransactionType::RECOVERY:
    case TransactionType::WRITE: {
        transaction->rollback(&wal);
        clearTransactionNoLock(transaction->getID());
    } break;
    default: {
        throw TransactionManagerException("Invalid transaction type to rollback.");
    }
    }
}

void TransactionManager::checkpoint(main::ClientContext& clientContext) {
    UniqLock lck{mtxForSerializingPublicFunctionCalls};
    if (clientContext.isInMemory()) {
        return;
    }
    checkpointNoLock(clientContext);
}

TransactionManager* TransactionManager::Get(const main::ClientContext& context) {
    if (context.getAttachedDatabase() != nullptr) {
        context.getAttachedDatabase()->getTransactionManager();
    }
    return context.getDatabase()->getTransactionManager();
}

UniqLock TransactionManager::stopNewTransactionsAndWaitUntilAllTransactionsLeave() {
    UniqLock startTransactionLock{mtxForStartingNewTransactions};
    uint64_t numTimesWaited = 0;
    while (true) {
        if (hasNoActiveTransactions()) {
            break;
        }
        numTimesWaited++;
        if (numTimesWaited * THREAD_SLEEP_TIME_WHEN_WAITING_IN_MICROS >
            checkpointWaitTimeoutInMicros) {
            throw TransactionManagerException(
                "Timeout waiting for active transactions to leave the system before "
                "checkpointing. If you have an open transaction, please close it and try "
                "again.");
        }
        std::this_thread::sleep_for(
            std::chrono::microseconds(THREAD_SLEEP_TIME_WHEN_WAITING_IN_MICROS));
    }
    return startTransactionLock;
}

bool TransactionManager::hasNoActiveTransactions() const {
    return activeTransactions.empty();
}

bool TransactionManager::hasActiveWriteTransactionNoLock() const {
    return std::ranges::any_of(activeTransactions,
        [](const auto& transaction) { return transaction->isWriteTransaction(); });
}

void TransactionManager::clearTransactionNoLock(transaction_t transactionID) {
    KU_ASSERT(std::ranges::any_of(activeTransactions.begin(), activeTransactions.end(),
        [transactionID](const auto& activeTransaction) {
            return activeTransaction->getID() == transactionID;
        }));
    std::erase_if(activeTransactions, [transactionID](const auto& activeTransaction) {
        return activeTransaction->getID() == transactionID;
    });
}

std::unique_ptr<Checkpointer> TransactionManager::initCheckpointer(
    main::ClientContext& clientContext) {
    return std::make_unique<Checkpointer>(clientContext);
}

void TransactionManager::checkpointNoLock(main::ClientContext& clientContext) {
    // Note: It is enough to stop and wait for transactions to leave the system instead of, for
    // example, checking on the query processor's task scheduler. This is because the
    // first and last steps that a connection performs when executing a query are to
    // start and commit/rollback transaction. The query processor also ensures that it
    // will only return results or error after all threads working on the tasks of a
    // query stop working on the tasks of the query and these tasks are removed from the
    // query.
    try {
        auto lockForStartingTransaction = stopNewTransactionsAndWaitUntilAllTransactionsLeave();
    } catch (std::exception& e) {
        throw CheckpointException{e};
    }
    auto checkpointer = initCheckpointerFunc(clientContext);
    try {
        checkpointer->writeCheckpoint();
    } catch (std::exception& e) {
        checkpointer->rollback();
        throw CheckpointException{e};
    }
}

} // namespace transaction
} // namespace lbug
