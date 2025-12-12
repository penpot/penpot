#pragma once

#include <memory>
#include <mutex>

#include "common/constants.h"
#include "common/uniq_lock.h"
#include "storage/checkpointer.h"
#include "storage/wal/wal.h"
#include "transaction/transaction.h"

namespace lbug {
namespace main {
class ClientContext;
} // namespace main

namespace testing {
class DBTest;
class FlakyBufferManager;
class FlakyCheckpointer;
} // namespace testing

namespace transaction {

class TransactionManager {
    friend class testing::DBTest;
    friend class testing::FlakyBufferManager;
    friend class testing::FlakyCheckpointer;

    using init_checkpointer_func_t =
        std::function<std::unique_ptr<storage::Checkpointer>(main::ClientContext&)>;
    static std::unique_ptr<storage::Checkpointer> initCheckpointer(
        main::ClientContext& clientContext);

public:
    // Timestamp starts from 1. 0 is reserved for the dummy system transaction.
    explicit TransactionManager(storage::WAL& wal)
        : wal{wal}, lastTransactionID{Transaction::START_TRANSACTION_ID}, lastTimestamp{1} {
        initCheckpointerFunc = initCheckpointer;
    }

    Transaction* beginTransaction(main::ClientContext& clientContext, TransactionType type);

    void commit(main::ClientContext& clientContext, Transaction* transaction);
    void rollback(main::ClientContext& clientContext, Transaction* transaction);

    void checkpoint(main::ClientContext& clientContext);

    static TransactionManager* Get(const main::ClientContext& context);

private:
    bool hasNoActiveTransactions() const;
    void checkpointNoLock(main::ClientContext& clientContext);

    // This functions locks the mutex to start new transactions.
    common::UniqLock stopNewTransactionsAndWaitUntilAllTransactionsLeave();

    bool hasActiveWriteTransactionNoLock() const;

    // Note: Used by DBTest::createDB only.
    void setCheckPointWaitTimeoutForTransactionsToLeaveInMicros(uint64_t waitTimeInMicros) {
        checkpointWaitTimeoutInMicros = waitTimeInMicros;
    }

    void clearTransactionNoLock(common::transaction_t transactionID);

private:
    storage::WAL& wal;
    std::vector<std::unique_ptr<Transaction>> activeTransactions;
    common::transaction_t lastTransactionID;
    common::transaction_t lastTimestamp;
    // This mutex is used to ensure thread safety and letting only one public function to be called
    // at any time except the stopNewTransactionsAndWaitUntilAllReadTransactionsLeave
    // function, which needs to let calls to coming and rollback.
    std::mutex mtxForSerializingPublicFunctionCalls;
    std::mutex mtxForStartingNewTransactions;
    uint64_t checkpointWaitTimeoutInMicros = common::DEFAULT_CHECKPOINT_WAIT_TIMEOUT_IN_MICROS;

    init_checkpointer_func_t initCheckpointerFunc;
};
} // namespace transaction
} // namespace lbug
