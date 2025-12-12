#include "main/database_manager.h"

#include "common/exception/runtime.h"
#include "common/string_utils.h"
#include "main/client_context.h"
#include "main/database.h"

using namespace lbug::common;

namespace lbug {
namespace main {

DatabaseManager::DatabaseManager() : defaultDatabase{""} {}

void DatabaseManager::registerAttachedDatabase(std::unique_ptr<AttachedDatabase> attachedDatabase) {
    if (defaultDatabase == "") {
        defaultDatabase = attachedDatabase->getDBName();
    }
    if (hasAttachedDatabase(attachedDatabase->getDBName())) {
        throw RuntimeException{stringFormat(
            "Duplicate attached database name: {}. Attached database name must be unique.",
            attachedDatabase->getDBName())};
    }
    attachedDatabases.push_back(std::move(attachedDatabase));
}

bool DatabaseManager::hasAttachedDatabase(const std::string& name) {
    auto upperCaseName = StringUtils::getUpper(name);
    for (auto& attachedDatabase : attachedDatabases) {
        auto attachedDBName = StringUtils::getUpper(attachedDatabase->getDBName());
        if (attachedDBName == upperCaseName) {
            return true;
        }
    }
    return false;
}

AttachedDatabase* DatabaseManager::getAttachedDatabase(const std::string& name) {
    auto upperCaseName = StringUtils::getUpper(name);
    for (auto& attachedDatabase : attachedDatabases) {
        auto attachedDBName = StringUtils::getUpper(attachedDatabase->getDBName());
        if (attachedDBName == upperCaseName) {
            return attachedDatabase.get();
        }
    }
    throw RuntimeException{stringFormat("No database named {}.", name)};
}

void DatabaseManager::detachDatabase(const std::string& databaseName) {
    auto upperCaseName = StringUtils::getUpper(databaseName);
    for (auto it = attachedDatabases.begin(); it != attachedDatabases.end(); ++it) {
        auto attachedDBName = (*it)->getDBName();
        StringUtils::toUpper(attachedDBName);
        if (attachedDBName == upperCaseName) {
            attachedDatabases.erase(it);
            return;
        }
    }
    throw RuntimeException{stringFormat("Database: {} doesn't exist.", databaseName)};
}

void DatabaseManager::setDefaultDatabase(const std::string& databaseName) {
    if (getAttachedDatabase(databaseName) == nullptr) {
        throw RuntimeException{stringFormat("No database named {}.", databaseName)};
    }
    defaultDatabase = databaseName;
}

std::vector<AttachedDatabase*> DatabaseManager::getAttachedDatabases() const {
    std::vector<AttachedDatabase*> attachedDatabasesPtr;
    for (auto& attachedDatabase : attachedDatabases) {
        attachedDatabasesPtr.push_back(attachedDatabase.get());
    }
    return attachedDatabasesPtr;
}

void DatabaseManager::invalidateCache() {
    for (auto& attachedDatabase : attachedDatabases) {
        attachedDatabase->invalidateCache();
    }
}

DatabaseManager* DatabaseManager::Get(const ClientContext& context) {
    return context.getDatabase()->getDatabaseManager();
}

} // namespace main
} // namespace lbug
