#include "catalog/catalog_entry/type_catalog_entry.h"

#include "common/serializer/deserializer.h"

namespace lbug {
namespace catalog {

void TypeCatalogEntry::serialize(common::Serializer& serializer) const {
    CatalogEntry::serialize(serializer);
    serializer.writeDebuggingInfo("type");
    type.serialize(serializer);
}

std::unique_ptr<TypeCatalogEntry> TypeCatalogEntry::deserialize(
    common::Deserializer& deserializer) {
    std::string debuggingInfo;
    auto typeCatalogEntry = std::make_unique<TypeCatalogEntry>();
    deserializer.validateDebuggingInfo(debuggingInfo, "type");
    typeCatalogEntry->type = common::LogicalType::deserialize(deserializer);
    return typeCatalogEntry;
}

} // namespace catalog
} // namespace lbug
