#include "catalog/catalog_entry/node_table_catalog_entry.h"

#include "binder/ddl/bound_create_table_info.h"
#include "common/serializer/deserializer.h"
#include "common/string_utils.h"

using namespace lbug::binder;

namespace lbug {
namespace catalog {

void NodeTableCatalogEntry::renameProperty(const std::string& propertyName,
    const std::string& newName) {
    TableCatalogEntry::renameProperty(propertyName, newName);
    if (common::StringUtils::caseInsensitiveEquals(propertyName, primaryKeyName)) {
        primaryKeyName = newName;
    }
}

void NodeTableCatalogEntry::serialize(common::Serializer& serializer) const {
    TableCatalogEntry::serialize(serializer);
    serializer.writeDebuggingInfo("primaryKeyName");
    serializer.write(primaryKeyName);
}

std::unique_ptr<NodeTableCatalogEntry> NodeTableCatalogEntry::deserialize(
    common::Deserializer& deserializer) {
    std::string debuggingInfo;
    std::string primaryKeyName;
    deserializer.validateDebuggingInfo(debuggingInfo, "primaryKeyName");
    deserializer.deserializeValue(primaryKeyName);
    auto nodeTableEntry = std::make_unique<NodeTableCatalogEntry>();
    nodeTableEntry->primaryKeyName = primaryKeyName;
    return nodeTableEntry;
}

std::string NodeTableCatalogEntry::toCypher(const ToCypherInfo& /*info*/) const {
    return common::stringFormat("CREATE NODE TABLE `{}` ({} PRIMARY KEY(`{}`));", getName(),
        propertyCollection.toCypher(), primaryKeyName);
}

std::unique_ptr<TableCatalogEntry> NodeTableCatalogEntry::copy() const {
    auto other = std::make_unique<NodeTableCatalogEntry>();
    other->primaryKeyName = primaryKeyName;
    other->copyFrom(*this);
    return other;
}

std::unique_ptr<BoundExtraCreateCatalogEntryInfo> NodeTableCatalogEntry::getBoundExtraCreateInfo(
    transaction::Transaction*) const {
    return std::make_unique<BoundExtraCreateNodeTableInfo>(primaryKeyName,
        copyVector(getProperties()));
}

} // namespace catalog
} // namespace lbug
