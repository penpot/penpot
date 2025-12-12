#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"

namespace lbug {
namespace catalog {

ScalarMacroCatalogEntry::ScalarMacroCatalogEntry(std::string name,
    std::unique_ptr<function::ScalarMacroFunction> macroFunction)
    : CatalogEntry{CatalogEntryType::SCALAR_MACRO_ENTRY, std::move(name)},
      macroFunction{std::move(macroFunction)} {}

void ScalarMacroCatalogEntry::serialize(common::Serializer& serializer) const {
    CatalogEntry::serialize(serializer);
    macroFunction->serialize(serializer);
}

std::unique_ptr<ScalarMacroCatalogEntry> ScalarMacroCatalogEntry::deserialize(
    common::Deserializer& deserializer) {
    auto scalarMacroCatalogEntry = std::make_unique<ScalarMacroCatalogEntry>();
    scalarMacroCatalogEntry->macroFunction =
        function::ScalarMacroFunction::deserialize(deserializer);
    return scalarMacroCatalogEntry;
}

std::string ScalarMacroCatalogEntry::toCypher(const ToCypherInfo& /*info*/) const {
    return macroFunction->toCypher(getName());
}

} // namespace catalog
} // namespace lbug
