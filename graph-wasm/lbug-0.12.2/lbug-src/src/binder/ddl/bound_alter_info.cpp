#include "binder/ddl/bound_alter_info.h"

namespace lbug {
namespace binder {

std::string BoundAlterInfo::toString() const {
    std::string result = "Operation: ";
    switch (alterType) {
    case common::AlterType::RENAME: {
        auto renameInfo = common::ku_dynamic_cast<BoundExtraRenameTableInfo*>(extraInfo.get());
        result += "Rename Table " + tableName + " to " + renameInfo->newName;
        break;
    }
    case common::AlterType::ADD_PROPERTY: {
        auto addPropInfo = common::ku_dynamic_cast<BoundExtraAddPropertyInfo*>(extraInfo.get());
        result +=
            "Add Property " + addPropInfo->propertyDefinition.getName() + " to Table " + tableName;
        break;
    }
    case common::AlterType::DROP_PROPERTY: {
        auto dropPropInfo = common::ku_dynamic_cast<BoundExtraDropPropertyInfo*>(extraInfo.get());
        result += "Drop Property " + dropPropInfo->propertyName + " from Table " + tableName;
        break;
    }
    case common::AlterType::RENAME_PROPERTY: {
        auto renamePropInfo =
            common::ku_dynamic_cast<BoundExtraRenamePropertyInfo*>(extraInfo.get());
        result += "Rename Property " + renamePropInfo->oldName + " to " + renamePropInfo->newName +
                  " in Table " + tableName;
        break;
    }
    case common::AlterType::COMMENT: {
        result += "Comment on Table " + tableName;
        break;
    }
    default:
        break;
    }
    return result;
}

} // namespace binder
} // namespace lbug
