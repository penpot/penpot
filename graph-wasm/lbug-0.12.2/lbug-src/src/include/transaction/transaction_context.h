#pragma once

#include <mutex>

#include "transaction.h"

namespace lbug {

namespace main {
class ClientContext;
}

namespace transaction {

/**
 * If the connection is in AUTO_COMMIT mode, any query over the connection will be wrapped around
 * a transaction and committed (even if the query is READ_ONLY).
 * If the connection is in MANUAL transaction mode, which happens only if an application
 * manually begins a transaction (see below), then an application has to manually commit or
 * rollback the transaction by calling commit() or rollback().
 *
 * AUTO_COMMIT is the default mode when a Connection is created. If an application calls
 * begin[ReadOnly/Write]Transaction at any point, the mode switches to MANUAL. This creates
 * an "active transaction" in the connection. When a connection is in MANUAL mode and the
 * active transaction is rolled back or committed, then the active transaction is removed (so
 * the connection no longer has an active transaction), and the mode automatically switches
 * back to AUTO_COMMIT.
 * Note: When a Connection object is deconstructed, if the connection has an active (manual)
 * transaction, then the active transaction is rolled back.
 */
enum class TransactionMode : uint8_t { AUTO = 0, MANUAL = 1 };

class LBUG_API TransactionContext {
public:
    explicit TransactionContext(main::ClientContext& clientContext);
    ~TransactionContext();

    bool isAutoTransaction() const { return mode == TransactionMode::AUTO; }

    void beginReadTransaction();
    void beginWriteTransaction();
    void beginAutoTransaction(bool readOnlyStatement);
    void beginRecoveryTransaction();
    void validateManualTransaction(bool readOnlyStatement) const;

    void commit();
    void rollback();

    TransactionMode getTransactionMode() const { return mode; }
    bool hasActiveTransaction() const { return activeTransaction != nullptr; }
    Transaction* getActiveTransaction() const { return activeTransaction; }

    void clearTransaction();

    static TransactionContext* Get(const main::ClientContext& context);

private:
    void beginTransactionInternal(TransactionType transactionType);

private:
    std::mutex mtx;
    main::ClientContext& clientContext;
    TransactionMode mode;
    Transaction* activeTransaction;
};

} // namespace transaction
} // namespace lbug
