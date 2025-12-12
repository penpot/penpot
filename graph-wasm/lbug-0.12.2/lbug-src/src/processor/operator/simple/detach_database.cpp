#include "processor/operator/simple/detach_database.h"

#include "main/client_context.h"
#include "main/database.h"
#include "main/database_manager.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace processor {

std::string DetatchDatabasePrintInfo::toString() const {
    return "Database: " + name;
}

void DetachDatabase::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto dbManager = main::DatabaseManager::Get(*clientContext);
    if (dbManager->hasAttachedDatabase(dbName) &&
        dbManager->getAttachedDatabase(dbName)->getDBType() == common::ATTACHED_LBUG_DB_TYPE) {
        clientContext->setDefaultDatabase(nullptr /* defaultDatabase */);
    }
    dbManager->detachDatabase(dbName);
    appendMessage("Detached database successfully.", storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
