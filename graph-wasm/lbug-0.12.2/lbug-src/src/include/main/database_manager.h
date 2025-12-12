#pragma once

#include "attached_database.h"

namespace lbug {
namespace main {

class DatabaseManager {
public:
    DatabaseManager();

    void registerAttachedDatabase(std::unique_ptr<AttachedDatabase> attachedDatabase);
    bool hasAttachedDatabase(const std::string& name);
    LBUG_API AttachedDatabase* getAttachedDatabase(const std::string& name);
    void detachDatabase(const std::string& databaseName);
    std::string getDefaultDatabase() const { return defaultDatabase; }
    bool hasDefaultDatabase() const { return defaultDatabase != ""; }
    void setDefaultDatabase(const std::string& databaseName);
    std::vector<AttachedDatabase*> getAttachedDatabases() const;
    LBUG_API void invalidateCache();

    LBUG_API static DatabaseManager* Get(const ClientContext& context);

private:
    std::vector<std::unique_ptr<AttachedDatabase>> attachedDatabases;
    std::string defaultDatabase;
};

} // namespace main
} // namespace lbug
