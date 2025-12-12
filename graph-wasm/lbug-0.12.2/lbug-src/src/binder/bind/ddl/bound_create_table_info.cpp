#include "binder/ddl/bound_create_table_info.h"

#include "catalog/catalog_entry/catalog_entry_type.h"
#include "catalog/catalog_entry/table_catalog_entry.h"

using namespace lbug::parser;
using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

std::string BoundCreateTableInfo::toString() const {
    std::string result = "";
    switch (type) {
    case CatalogEntryType::NODE_TABLE_ENTRY: {
        result += "Create Node Table: ";
        result += tableName;
        result += ",Properties: ";
        auto nodeInfo = extraInfo->ptrCast<BoundExtraCreateNodeTableInfo>();
        for (auto& definition : nodeInfo->propertyDefinitions) {
            result += definition.getName();
            result += ", ";
        }
    } break;
    case CatalogEntryType::REL_GROUP_ENTRY: {
        result += "Create Relationship Table: ";
        result += tableName;
        auto relGroupInfo = extraInfo->ptrCast<BoundExtraCreateRelTableGroupInfo>();
        result += "Properties: ";
        for (auto& definition : relGroupInfo->propertyDefinitions) {
            result += definition.getName();
            result += ", ";
        }
    } break;
    default:
        break;
    }
    return result;
}

} // namespace binder
} // namespace lbug
