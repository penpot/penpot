#pragma once

#include "table_catalog_entry.h"

namespace lbug {
namespace transaction {
class Transaction;
} // namespace transaction

namespace catalog {

class Catalog;
class LBUG_API NodeTableCatalogEntry final : public TableCatalogEntry {
    static constexpr CatalogEntryType entryType_ = CatalogEntryType::NODE_TABLE_ENTRY;

public:
    NodeTableCatalogEntry() = default;
    NodeTableCatalogEntry(std::string name, std::string primaryKeyName)
        : TableCatalogEntry{entryType_, std::move(name)},
          primaryKeyName{std::move(primaryKeyName)} {}

    bool isParent(common::table_id_t /*tableID*/) override { return false; }
    common::TableType getTableType() const override { return common::TableType::NODE; }

    std::string getPrimaryKeyName() const { return primaryKeyName; }
    common::property_id_t getPrimaryKeyID() const {
        return propertyCollection.getPropertyID(primaryKeyName);
    }
    const binder::PropertyDefinition& getPrimaryKeyDefinition() const {
        return getProperty(primaryKeyName);
    }

    void renameProperty(const std::string& propertyName, const std::string& newName) override;

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<NodeTableCatalogEntry> deserialize(common::Deserializer& deserializer);

    std::unique_ptr<TableCatalogEntry> copy() const override;
    std::string toCypher(const ToCypherInfo& info) const override;

private:
    std::unique_ptr<binder::BoundExtraCreateCatalogEntryInfo> getBoundExtraCreateInfo(
        transaction::Transaction* transaction) const override;

private:
    std::string primaryKeyName;
};

} // namespace catalog
} // namespace lbug
