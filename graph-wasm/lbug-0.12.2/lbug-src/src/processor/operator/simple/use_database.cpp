#include "processor/operator/simple/use_database.h"

#include "main/client_context.h"
#include "main/database_manager.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace processor {

void UseDatabase::executeInternal(ExecutionContext* context) {
    auto dbManager = main::DatabaseManager::Get(*context->clientContext);
    dbManager->setDefaultDatabase(dbName);
    appendMessage("Used database successfully.",
        storage::MemoryManager::Get(*context->clientContext));
}

std::string UseDatabasePrintInfo::toString() const {
    std::string result = "Database: ";
    result += dbName;
    return result;
}

} // namespace processor
} // namespace lbug
