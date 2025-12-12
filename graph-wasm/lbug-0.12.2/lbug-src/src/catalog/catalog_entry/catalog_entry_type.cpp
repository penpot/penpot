#include "catalog/catalog_entry/catalog_entry_type.h"

#include "common/assert.h"

namespace lbug {
namespace catalog {

std::string CatalogEntryTypeUtils::toString(CatalogEntryType type) {
    switch (type) {
    case CatalogEntryType::NODE_TABLE_ENTRY:
        return "NODE_TABLE_ENTRY";
    case CatalogEntryType::REL_GROUP_ENTRY:
        return "REL_GROUP_ENTRY";
    case CatalogEntryType::FOREIGN_TABLE_ENTRY:
        return "FOREIGN_TABLE_ENTRY";
    case CatalogEntryType::SCALAR_MACRO_ENTRY:
        return "SCALAR_MACRO_ENTRY";
    case CatalogEntryType::AGGREGATE_FUNCTION_ENTRY:
        return "AGGREGATE_FUNCTION_ENTRY";
    case CatalogEntryType::SCALAR_FUNCTION_ENTRY:
        return "SCALAR_FUNCTION_ENTRY";
    case CatalogEntryType::REWRITE_FUNCTION_ENTRY:
        return "REWRITE_FUNCTION_ENTRY";
    case CatalogEntryType::TABLE_FUNCTION_ENTRY:
        return "TABLE_FUNCTION_ENTRY";
    case CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY:
        return "STANDALONE_TABLE_FUNCTION_ENTRY";
    case CatalogEntryType::COPY_FUNCTION_ENTRY:
        return "COPY_FUNCTION_ENTRY";
    case CatalogEntryType::DUMMY_ENTRY:
        return "DUMMY_ENTRY";
    case CatalogEntryType::SEQUENCE_ENTRY:
        return "SEQUENCE_ENTRY";
    default:
        KU_UNREACHABLE;
    }
}

std::string FunctionEntryTypeUtils::toString(CatalogEntryType type) {
    switch (type) {
    case CatalogEntryType::SCALAR_MACRO_ENTRY:
        return "MACRO FUNCTION";
    case CatalogEntryType::AGGREGATE_FUNCTION_ENTRY:
        return "AGGREGATE FUNCTION";
    case CatalogEntryType::SCALAR_FUNCTION_ENTRY:
        return "SCALAR FUNCTION";
    case CatalogEntryType::REWRITE_FUNCTION_ENTRY:
        return "REWRITE FUNCTION";
    case CatalogEntryType::TABLE_FUNCTION_ENTRY:
        return "TABLE FUNCTION";
    case CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY:
        return "STANDALONE TABLE FUNCTION";
    case CatalogEntryType::COPY_FUNCTION_ENTRY:
        return "COPY FUNCTION";
    default:
        KU_UNREACHABLE;
    }
}

} // namespace catalog
} // namespace lbug
