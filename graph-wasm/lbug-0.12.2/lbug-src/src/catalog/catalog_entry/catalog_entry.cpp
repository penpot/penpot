#include "catalog/catalog_entry/catalog_entry.h"

#include "catalog/catalog_entry/index_catalog_entry.h"
#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "catalog/catalog_entry/type_catalog_entry.h"
#include "common/serializer/deserializer.h"
#include "transaction/transaction.h"

namespace lbug {
namespace catalog {

void CatalogEntry::serialize(common::Serializer& serializer) const {
    serializer.writeDebuggingInfo("type");
    serializer.write(type);
    serializer.writeDebuggingInfo("name");
    serializer.write(name);
    serializer.writeDebuggingInfo("oid");
    serializer.write(oid);
    serializer.writeDebuggingInfo("hasParent_");
    serializer.write(hasParent_);
}

std::unique_ptr<CatalogEntry> CatalogEntry::deserialize(common::Deserializer& deserializer) {
    std::string debuggingInfo;
    auto type = CatalogEntryType::DUMMY_ENTRY;
    std::string name;
    common::oid_t oid = common::INVALID_OID;
    bool hasParent_ = false;
    deserializer.validateDebuggingInfo(debuggingInfo, "type");
    deserializer.deserializeValue(type);
    deserializer.validateDebuggingInfo(debuggingInfo, "name");
    deserializer.deserializeValue(name);
    deserializer.validateDebuggingInfo(debuggingInfo, "oid");
    deserializer.deserializeValue(oid);
    deserializer.validateDebuggingInfo(debuggingInfo, "hasParent_");
    deserializer.deserializeValue(hasParent_);
    std::unique_ptr<CatalogEntry> entry;
    switch (type) {
    case CatalogEntryType::NODE_TABLE_ENTRY:
    case CatalogEntryType::REL_GROUP_ENTRY: {
        entry = TableCatalogEntry::deserialize(deserializer, type);
    } break;
    case CatalogEntryType::SCALAR_MACRO_ENTRY: {
        entry = ScalarMacroCatalogEntry::deserialize(deserializer);
    } break;
    case CatalogEntryType::SEQUENCE_ENTRY: {
        entry = SequenceCatalogEntry::deserialize(deserializer);
    } break;
    case CatalogEntryType::TYPE_ENTRY: {
        entry = TypeCatalogEntry::deserialize(deserializer);
    } break;
    case CatalogEntryType::INDEX_ENTRY: {
        entry = IndexCatalogEntry::deserialize(deserializer);
    } break;
    default:
        KU_UNREACHABLE;
    }
    entry->type = type;
    entry->name = std::move(name);
    entry->oid = oid;
    entry->hasParent_ = hasParent_;
    entry->timestamp = transaction::Transaction::DUMMY_START_TIMESTAMP;
    return entry;
}

void CatalogEntry::copyFrom(const CatalogEntry& other) {
    type = other.type;
    name = other.name;
    oid = other.oid;
    timestamp = other.timestamp;
    deleted = other.deleted;
    hasParent_ = other.hasParent_;
}

} // namespace catalog
} // namespace lbug
