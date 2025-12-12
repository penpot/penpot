#include "catalog/catalog_entry/table_catalog_entry.h"

#include "binder/ddl/bound_alter_info.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/serializer/deserializer.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace catalog {

std::unique_ptr<TableCatalogEntry> TableCatalogEntry::alter(transaction_t timestamp,
    const BoundAlterInfo& alterInfo, CatalogSet* tables) const {
    KU_ASSERT(!deleted);
    auto newEntry = copy();
    switch (alterInfo.alterType) {
    case AlterType::RENAME: {
        auto& renameTableInfo = *alterInfo.extraInfo->constPtrCast<BoundExtraRenameTableInfo>();
        newEntry->rename(renameTableInfo.newName);
    } break;
    case AlterType::RENAME_PROPERTY: {
        auto& renamePropInfo = *alterInfo.extraInfo->constPtrCast<BoundExtraRenamePropertyInfo>();
        newEntry->renameProperty(renamePropInfo.oldName, renamePropInfo.newName);
    } break;
    case AlterType::ADD_PROPERTY: {
        auto& addPropInfo = *alterInfo.extraInfo->constPtrCast<BoundExtraAddPropertyInfo>();
        newEntry->addProperty(addPropInfo.propertyDefinition);
    } break;
    case AlterType::DROP_PROPERTY: {
        auto& dropPropInfo = *alterInfo.extraInfo->constPtrCast<BoundExtraDropPropertyInfo>();
        newEntry->dropProperty(dropPropInfo.propertyName);
    } break;
    case AlterType::COMMENT: {
        auto& commentInfo = *alterInfo.extraInfo->constPtrCast<BoundExtraCommentInfo>();
        newEntry->setComment(commentInfo.comment);
    } break;
    case AlterType::ADD_FROM_TO_CONNECTION: {
        auto& connectionInfo =
            *alterInfo.extraInfo->constPtrCast<BoundExtraAlterFromToConnection>();
        newEntry->ptrCast<RelGroupCatalogEntry>()->addFromToConnection(connectionInfo.fromTableID,
            connectionInfo.toTableID, tables->getNextOIDNoLock());
    } break;
    case AlterType::DROP_FROM_TO_CONNECTION: {
        auto& connectionInfo =
            *alterInfo.extraInfo->constPtrCast<BoundExtraAlterFromToConnection>();
        newEntry->ptrCast<RelGroupCatalogEntry>()->dropFromToConnection(connectionInfo.fromTableID,
            connectionInfo.toTableID);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    newEntry->setOID(oid);
    newEntry->setTimestamp(timestamp);
    return newEntry;
}

column_id_t TableCatalogEntry::getMaxColumnID() const {
    return propertyCollection.getMaxColumnID();
}

void TableCatalogEntry::vacuumColumnIDs(column_id_t nextColumnID) {
    propertyCollection.vacuumColumnIDs(nextColumnID);
}

bool TableCatalogEntry::containsProperty(const std::string& propertyName) const {
    return propertyCollection.contains(propertyName);
}

property_id_t TableCatalogEntry::getPropertyID(const std::string& propertyName) const {
    return propertyCollection.getPropertyID(propertyName);
}

const PropertyDefinition& TableCatalogEntry::getProperty(const std::string& propertyName) const {
    return propertyCollection.getDefinition(propertyName);
}

const PropertyDefinition& TableCatalogEntry::getProperty(idx_t idx) const {
    return propertyCollection.getDefinition(idx);
}

column_id_t TableCatalogEntry::getColumnID(const std::string& propertyName) const {
    return propertyCollection.getColumnID(propertyName);
}

common::column_id_t TableCatalogEntry::getColumnID(common::idx_t idx) const {
    return propertyCollection.getColumnID(idx);
}

void TableCatalogEntry::addProperty(const PropertyDefinition& propertyDefinition) {
    propertyCollection.add(propertyDefinition);
}

void TableCatalogEntry::dropProperty(const std::string& propertyName) {
    propertyCollection.drop(propertyName);
}

void TableCatalogEntry::renameProperty(const std::string& propertyName,
    const std::string& newName) {
    propertyCollection.rename(propertyName, newName);
}

void TableCatalogEntry::serialize(Serializer& serializer) const {
    CatalogEntry::serialize(serializer);
    serializer.writeDebuggingInfo("comment");
    serializer.write(comment);
    serializer.writeDebuggingInfo("properties");
    propertyCollection.serialize(serializer);
}

std::unique_ptr<TableCatalogEntry> TableCatalogEntry::deserialize(Deserializer& deserializer,
    CatalogEntryType type) {
    std::string debuggingInfo;
    std::string comment;
    deserializer.validateDebuggingInfo(debuggingInfo, "comment");
    deserializer.deserializeValue(comment);
    deserializer.validateDebuggingInfo(debuggingInfo, "properties");
    auto propertyCollection = PropertyDefinitionCollection::deserialize(deserializer);
    std::unique_ptr<TableCatalogEntry> result;
    switch (type) {
    case CatalogEntryType::NODE_TABLE_ENTRY:
        result = NodeTableCatalogEntry::deserialize(deserializer);
        break;
    case CatalogEntryType::REL_GROUP_ENTRY:
        result = RelGroupCatalogEntry::deserialize(deserializer);
        break;
    default:
        KU_UNREACHABLE;
    }
    result->comment = std::move(comment);
    result->propertyCollection = std::move(propertyCollection);
    return result;
}

void TableCatalogEntry::copyFrom(const CatalogEntry& other) {
    CatalogEntry::copyFrom(other);
    auto& otherTable = ku_dynamic_cast<const TableCatalogEntry&>(other);
    comment = otherTable.comment;
    propertyCollection = otherTable.propertyCollection.copy();
}

BoundCreateTableInfo TableCatalogEntry::getBoundCreateTableInfo(
    transaction::Transaction* transaction, bool isInternal) const {
    auto extraInfo = getBoundExtraCreateInfo(transaction);
    return BoundCreateTableInfo(type, name, ConflictAction::ON_CONFLICT_THROW, std::move(extraInfo),
        isInternal, hasParent_);
}

} // namespace catalog
} // namespace lbug
