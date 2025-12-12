#include "processor/operator/simple/import_db.h"

#include "common/exception/runtime.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "transaction/transaction_context.h"

using namespace lbug::common;
using namespace lbug::transaction;
using namespace lbug::catalog;

namespace lbug {
namespace processor {

static void validateQueryResult(main::QueryResult* queryResult) {
    auto currentResult = queryResult;
    while (currentResult) {
        if (!currentResult->isSuccess()) {
            throw RuntimeException("Import database failed: " + currentResult->getErrorMessage());
        }
        currentResult = currentResult->getNextQueryResult();
    }
}

void ImportDB::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    if (query.empty()) { // Export empty database.
        appendMessage("Imported database successfully.",
            storage::MemoryManager::Get(*clientContext));
        return;
    }
    // TODO(Guodong): this is special for "Import database". Should refactor after we support
    // multiple DDL and COPY statements in a single transaction.
    // Currently, we split multiple query statements into single query and execute them one by one,
    // each with an auto transaction.
    auto transactionContext = transaction::TransactionContext::Get(*clientContext);
    if (transactionContext->hasActiveTransaction()) {
        transactionContext->commit();
    }
    auto res = clientContext->queryNoLock(query);
    validateQueryResult(res.get());
    if (!indexQuery.empty()) {
        res = clientContext->queryNoLock(indexQuery);
        validateQueryResult(res.get());
    }
    appendMessage("Imported database successfully.", storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
