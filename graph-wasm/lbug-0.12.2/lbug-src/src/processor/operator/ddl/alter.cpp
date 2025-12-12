#include "processor/operator/ddl/alter.h"

#include "catalog/catalog.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/enums/alter_type.h"
#include "common/exception/binder.h"
#include "common/exception/runtime.h"
#include "processor/execution_context.h"
#include "storage/storage_manager.h"
#include "storage/table/table.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

void Alter::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    if (defaultValueEvaluator) {
        defaultValueEvaluator->init(*resultSet, context->clientContext);
    }
}

void Alter::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = Transaction::Get(*clientContext);
    if (catalog->containsTable(transaction, info.tableName)) {
        auto entry = catalog->getTableCatalogEntry(transaction, info.tableName);
        alterTable(clientContext, *entry, info);
    } else {
        throw BinderException("Table " + info.tableName + " does not exist.");
    }
}

using on_conflict_throw_action = std::function<void()>;

static void validate(ConflictAction action, const on_conflict_throw_action& throwAction) {
    switch (action) {
    case ConflictAction::ON_CONFLICT_THROW: {
        throwAction();
    } break;
    case ConflictAction::ON_CONFLICT_DO_NOTHING:
        break;
    default:
        KU_UNREACHABLE;
    }
}

static std::string propertyNotInTableMessage(const std::string& tableName,
    const std::string& propertyName) {
    return stringFormat("{} table does not have property {}.", tableName, propertyName);
}

static void validatePropertyExist(ConflictAction action, const TableCatalogEntry& tableEntry,
    const std::string& propertyName) {
    validate(action, [&tableEntry, &propertyName]() {
        if (!tableEntry.containsProperty(propertyName)) {
            throw RuntimeException(propertyNotInTableMessage(tableEntry.getName(), propertyName));
        }
    });
}

static std::string propertyInTableMessage(const std::string& tableName,
    const std::string& propertyName) {
    return stringFormat("{} table already has property {}.", tableName, propertyName);
}

static void validatePropertyNotExist(ConflictAction action, const TableCatalogEntry& tableEntry,
    const std::string& propertyName) {
    validate(action, [&tableEntry, &propertyName] {
        if (tableEntry.containsProperty(propertyName)) {
            throw RuntimeException(propertyInTableMessage(tableEntry.getName(), propertyName));
        }
    });
}

using skip_alter_on_conflict = std::function<bool()>;

static bool skipAlter(ConflictAction action, const skip_alter_on_conflict& skipAlterOnConflict) {
    switch (action) {
    case ConflictAction::ON_CONFLICT_THROW:
        return false;
    case ConflictAction::ON_CONFLICT_DO_NOTHING:
        return skipAlterOnConflict();
    default:
        KU_UNREACHABLE;
    }
}

static bool checkAddPropertyConflicts(const TableCatalogEntry& tableEntry,
    const BoundAlterInfo& info) {
    const auto& extraInfo = info.extraInfo->constCast<BoundExtraAddPropertyInfo>();
    auto propertyName = extraInfo.propertyDefinition.getName();
    validatePropertyNotExist(info.onConflict, tableEntry, propertyName);

    // Eventually, we want to support non-constant default on rel tables, but it is non-trivial
    // due to FWD/BWD storage
    if (tableEntry.getType() == CatalogEntryType::REL_GROUP_ENTRY &&
        extraInfo.boundDefault->expressionType != ExpressionType::LITERAL) {
        throw RuntimeException(
            "Cannot set a non-constant default value when adding columns on REL tables.");
    }

    return skipAlter(info.onConflict,
        [&tableEntry, &propertyName]() { return tableEntry.containsProperty(propertyName); });
}

static bool checkDropPropertyConflicts(const TableCatalogEntry& tableEntry,
    const BoundAlterInfo& info, main::ClientContext& context) {
    const auto& extraInfo = info.extraInfo->constCast<BoundExtraDropPropertyInfo>();
    auto propertyName = extraInfo.propertyName;
    validatePropertyExist(info.onConflict, tableEntry, propertyName);
    if (tableEntry.containsProperty(propertyName)) {
        // Check constrains if we are going to drop a property that exists.
        auto propertyID = tableEntry.getPropertyID(propertyName);
        // Check primary key constraint
        if (tableEntry.getTableType() == TableType::NODE &&
            tableEntry.constCast<NodeTableCatalogEntry>().getPrimaryKeyID() == propertyID) {
            throw BinderException(stringFormat(
                "Cannot drop property {} in table {} because it is used as primary key.",
                propertyName, tableEntry.getName()));
        }
        // Check secondary index constraints
        auto catalog = Catalog::Get(context);
        auto transaction = transaction::Transaction::Get(context);
        if (catalog->containsIndex(transaction, tableEntry.getTableID(), propertyID)) {
            throw BinderException(stringFormat(
                "Cannot drop property {} in table {} because it is used in one or more indexes. "
                "Please remove the associated indexes before attempting to drop this property.",
                propertyName, tableEntry.getName()));
        }
    }
    return skipAlter(info.onConflict,
        [&tableEntry, &propertyName]() { return !tableEntry.containsProperty(propertyName); });
}

static bool checkRenamePropertyConflicts(const TableCatalogEntry& tableEntry,
    const BoundAlterInfo& info) {
    const auto* extraInfo = info.extraInfo->constPtrCast<BoundExtraRenamePropertyInfo>();
    validatePropertyExist(ConflictAction::ON_CONFLICT_THROW, tableEntry, extraInfo->oldName);
    validatePropertyNotExist(ConflictAction::ON_CONFLICT_THROW, tableEntry, extraInfo->newName);
    return false;
}

static bool checkRenameTableConflicts(const BoundAlterInfo& info, main::ClientContext& context) {
    auto newName = info.extraInfo->constCast<BoundExtraRenameTableInfo>().newName;
    auto catalog = Catalog::Get(context);
    auto transaction = transaction::Transaction::Get(context);
    if (catalog->containsTable(transaction, newName)) {
        throw BinderException("Table " + newName + " already exists.");
    }
    return false;
}

static std::string fromToInTableMessage(const std::string& relGroupName,
    const std::string& fromTableName, const std::string& toTableName) {
    return stringFormat("{}->{} already exists in {} table.", fromTableName, toTableName,
        relGroupName);
}

static bool checkAddFromToConflicts(const TableCatalogEntry& tableEntry, const BoundAlterInfo& info,
    main::ClientContext& context) {
    auto& extraInfo = info.extraInfo->constCast<BoundExtraAlterFromToConnection>();
    auto& relGroupEntry = tableEntry.constCast<RelGroupCatalogEntry>();
    validate(info.onConflict, [&relGroupEntry, &extraInfo, &context]() {
        if (relGroupEntry.hasRelEntryInfo(extraInfo.fromTableID, extraInfo.toTableID)) {
            auto catalog = Catalog::Get(context);
            auto transaction = transaction::Transaction::Get(context);
            auto fromTableName =
                catalog->getTableCatalogEntry(transaction, extraInfo.fromTableID)->getName();
            auto toTableName =
                catalog->getTableCatalogEntry(transaction, extraInfo.toTableID)->getName();
            throw BinderException{
                fromToInTableMessage(relGroupEntry.getName(), fromTableName, toTableName)};
        }
    });
    return skipAlter(info.onConflict, [&relGroupEntry, &extraInfo]() {
        return relGroupEntry.hasRelEntryInfo(extraInfo.fromTableID, extraInfo.toTableID);
    });
}

static std::string fromToNotInTableMessage(const std::string& relGroupName,
    const std::string& fromTableName, const std::string& toTableName) {
    return stringFormat("{}->{} does not exist in {} table.", fromTableName, toTableName,
        relGroupName);
}

static bool checkDropFromToConflicts(const TableCatalogEntry& tableEntry,
    const BoundAlterInfo& info, main::ClientContext& context) {
    auto& extraInfo = info.extraInfo->constCast<BoundExtraAlterFromToConnection>();
    auto& relGroupEntry = tableEntry.constCast<RelGroupCatalogEntry>();
    validate(info.onConflict, [&relGroupEntry, &extraInfo, &context]() {
        if (!relGroupEntry.hasRelEntryInfo(extraInfo.fromTableID, extraInfo.toTableID)) {
            auto catalog = Catalog::Get(context);
            auto transaction = transaction::Transaction::Get(context);
            auto fromTableName =
                catalog->getTableCatalogEntry(transaction, extraInfo.fromTableID)->getName();
            auto toTableName =
                catalog->getTableCatalogEntry(transaction, extraInfo.toTableID)->getName();
            throw BinderException{
                fromToNotInTableMessage(relGroupEntry.getName(), fromTableName, toTableName)};
        }
    });
    return skipAlter(info.onConflict, [&relGroupEntry, &extraInfo]() {
        return !relGroupEntry.hasRelEntryInfo(extraInfo.fromTableID, extraInfo.toTableID);
    });
}

void Alter::alterTable(main::ClientContext* clientContext, const TableCatalogEntry& entry,
    const BoundAlterInfo& alterInfo) {
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = Transaction::Get(*clientContext);
    auto memoryManager = storage::MemoryManager::Get(*clientContext);
    auto tableName = entry.getName();
    switch (info.alterType) {
    case AlterType::ADD_PROPERTY: {
        auto& extraInfo = info.extraInfo->constCast<BoundExtraAddPropertyInfo>();
        auto propertyName = extraInfo.propertyDefinition.getName();
        if (checkAddPropertyConflicts(entry, info)) {
            appendMessage(propertyInTableMessage(tableName, propertyName), memoryManager);
            return;
        }
        appendMessage(stringFormat("Property {} added to table {}.", propertyName, tableName),
            memoryManager);
    } break;
    case AlterType::DROP_PROPERTY: {
        auto& extraInfo = info.extraInfo->constCast<BoundExtraDropPropertyInfo>();
        auto propertyName = extraInfo.propertyName;
        if (checkDropPropertyConflicts(entry, info, *clientContext)) {
            appendMessage(propertyNotInTableMessage(tableName, propertyName), memoryManager);
            return;
        }
        appendMessage(
            stringFormat("Property {} has been dropped from table {}.", propertyName, tableName),
            memoryManager);
    } break;
    case AlterType::RENAME_PROPERTY: {
        // Rename property does not have IF EXISTS
        checkRenamePropertyConflicts(entry, info);
        auto& extraInfo = info.extraInfo->constCast<BoundExtraRenamePropertyInfo>();
        appendMessage(
            stringFormat("Property {} renamed to {}.", extraInfo.oldName, extraInfo.newName),
            memoryManager);
    } break;
    case AlterType::RENAME: {
        // Rename table does not have IF EXISTS
        checkRenameTableConflicts(info, *clientContext);
        auto& extraInfo = info.extraInfo->constCast<BoundExtraRenameTableInfo>();
        appendMessage(stringFormat("Table {} renamed to {}.", tableName, extraInfo.newName),
            memoryManager);
    } break;
    case AlterType::ADD_FROM_TO_CONNECTION: {
        auto& extraInfo = info.extraInfo->constCast<BoundExtraAlterFromToConnection>();
        auto fromTableName =
            catalog->getTableCatalogEntry(transaction, extraInfo.fromTableID)->getName();
        auto toTableName =
            catalog->getTableCatalogEntry(transaction, extraInfo.toTableID)->getName();
        if (checkAddFromToConflicts(entry, info, *clientContext)) {
            appendMessage(fromToInTableMessage(tableName, fromTableName, toTableName),
                memoryManager);
            return;
        }
        appendMessage(
            stringFormat("{}->{} added to table {}.", fromTableName, toTableName, tableName),
            memoryManager);
    } break;
    case AlterType::DROP_FROM_TO_CONNECTION: {
        auto& extraInfo = info.extraInfo->constCast<BoundExtraAlterFromToConnection>();
        auto fromTableName =
            catalog->getTableCatalogEntry(transaction, extraInfo.fromTableID)->getName();
        auto toTableName =
            catalog->getTableCatalogEntry(transaction, extraInfo.toTableID)->getName();
        if (checkDropFromToConflicts(entry, info, *clientContext)) {
            appendMessage(fromToNotInTableMessage(tableName, fromTableName, toTableName),
                memoryManager);
            return;
        }
        appendMessage(stringFormat("{}->{} has been dropped from table {}.", fromTableName,
                          toTableName, tableName),
            memoryManager);
    } break;
    case AlterType::COMMENT: {
        appendMessage(stringFormat("Comment added to table {}.", tableName), memoryManager);
    } break;
    default:
        KU_UNREACHABLE;
    }

    // Handle storage changes
    const auto storageManager = storage::StorageManager::Get(*clientContext);
    catalog->alterTableEntry(transaction, alterInfo);
    // We don't use an optimistic allocator in this case since rollback of new columns is already
    // handled by checkpoint
    auto& pageAllocator = *storageManager->getDataFH()->getPageManager();
    switch (info.alterType) {
    case AlterType::ADD_PROPERTY: {
        auto& boundAddPropInfo = info.extraInfo->constCast<BoundExtraAddPropertyInfo>();
        KU_ASSERT(defaultValueEvaluator);
        auto* alteredEntry = catalog->getTableCatalogEntry(transaction, alterInfo.tableName);
        auto& addedProp = alteredEntry->getProperty(boundAddPropInfo.propertyDefinition.getName());
        storage::TableAddColumnState state{addedProp, *defaultValueEvaluator};
        switch (alteredEntry->getTableType()) {
        case TableType::NODE: {
            storageManager->getTable(alteredEntry->getTableID())
                ->addColumn(transaction, state, pageAllocator);
        } break;
        case TableType::REL: {
            for (auto& innerRelEntry :
                alteredEntry->cast<RelGroupCatalogEntry>().getRelEntryInfos()) {
                auto* relTable = storageManager->getTable(innerRelEntry.oid);
                relTable->addColumn(transaction, state, pageAllocator);
            }
        } break;
        default: {
            KU_UNREACHABLE;
        }
        }
    } break;
    case AlterType::DROP_PROPERTY: {
        auto* alteredEntry = catalog->getTableCatalogEntry(transaction, alterInfo.tableName);
        switch (alteredEntry->getTableType()) {
        case TableType::NODE: {
            storageManager->getTable(alteredEntry->getTableID())->dropColumn();
        } break;
        case TableType::REL: {
            for (auto& innerRelEntry :
                alteredEntry->cast<RelGroupCatalogEntry>().getRelEntryInfos()) {
                auto* relTable = storageManager->getTable(innerRelEntry.oid);
                relTable->dropColumn();
            }
        } break;
        default: {
            KU_UNREACHABLE;
        }
        }
    } break;
    case AlterType::ADD_FROM_TO_CONNECTION: {
        auto relGroupEntry = catalog->getTableCatalogEntry(transaction, alterInfo.tableName)
                                 ->ptrCast<RelGroupCatalogEntry>();
        auto connectionInfo = alterInfo.extraInfo->constPtrCast<BoundExtraAlterFromToConnection>();
        auto relEntryInfo =
            relGroupEntry->getRelEntryInfo(connectionInfo->fromTableID, connectionInfo->toTableID);
        storageManager->addRelTable(relGroupEntry, *relEntryInfo);
    } break;
    default:
        break;
    }
}

} // namespace processor
} // namespace lbug
