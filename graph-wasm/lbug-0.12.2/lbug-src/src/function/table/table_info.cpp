#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/enums/extend_direction_util.h"
#include "common/exception/catalog.h"
#include "common/string_utils.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "main/database_manager.h"

using namespace lbug::catalog;
using namespace lbug::common;

namespace lbug {
namespace function {

struct ExtraPropertyInfo {
    virtual ~ExtraPropertyInfo() = default;

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

    virtual std::unique_ptr<ExtraPropertyInfo> copy() const = 0;
};

struct ExtraNodePropertyInfo : ExtraPropertyInfo {
    bool isPrimaryKey;

    explicit ExtraNodePropertyInfo(bool isPrimaryKey) : isPrimaryKey{isPrimaryKey} {}

    std::unique_ptr<ExtraPropertyInfo> copy() const override {
        return std::make_unique<ExtraNodePropertyInfo>(isPrimaryKey);
    }
};

struct ExtraRelPropertyInfo : ExtraPropertyInfo {
    std::string storageDirection;

    explicit ExtraRelPropertyInfo(std::string storageDirection)
        : storageDirection{std::move(storageDirection)} {}

    std::unique_ptr<ExtraPropertyInfo> copy() const override {
        return std::make_unique<ExtraRelPropertyInfo>(storageDirection);
    }
};

struct PropertyInfo {
    column_id_t propertyID = INVALID_COLUMN_ID;
    std::string name;
    std::string type;
    std::string defaultVal;
    std::unique_ptr<ExtraPropertyInfo> extraInfo = nullptr;

    PropertyInfo() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(PropertyInfo);

private:
    PropertyInfo(const PropertyInfo& other)
        : propertyID{other.propertyID}, name{other.name}, type{other.type},
          defaultVal{other.defaultVal} {
        if (other.extraInfo) {
            extraInfo = other.extraInfo->copy();
        }
    }
};

struct TableInfoBindData final : TableFuncBindData {
    CatalogEntryType type;
    std::vector<PropertyInfo> infos;

    TableInfoBindData(CatalogEntryType type, std::vector<PropertyInfo> infos,
        binder::expression_vector columns)
        : TableFuncBindData{std::move(columns), infos.size()}, type{type}, infos{std::move(infos)} {
    }

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<TableInfoBindData>(type, copyVector(infos), columns);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto bindData = input.bindData->constPtrCast<TableInfoBindData>();
    auto i = 0u;
    auto size = morsel.getMorselSize();
    for (; i < size; i++) {
        auto& info = bindData->infos[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, info.propertyID);
        output.getValueVectorMutable(1).setValue(i, info.name);
        output.getValueVectorMutable(2).setValue(i, info.type);
        output.getValueVectorMutable(3).setValue(i, info.defaultVal);
        switch (bindData->type) {
        case CatalogEntryType::NODE_TABLE_ENTRY: {
            auto extraInfo = info.extraInfo->ptrCast<ExtraNodePropertyInfo>();
            output.getValueVectorMutable(4).setValue(i, extraInfo->isPrimaryKey);
        } break;
        case CatalogEntryType::REL_GROUP_ENTRY: {
            auto extraInfo = info.extraInfo->ptrCast<ExtraRelPropertyInfo>();
            output.getValueVectorMutable(4).setValue(i, extraInfo->storageDirection);
        } break;
        default:
            break;
        }
    }
    return i;
}

static PropertyInfo getInfo(const binder::PropertyDefinition& def) {
    auto info = PropertyInfo();
    info.name = def.getName();
    info.type = def.getType().toString();
    info.defaultVal = def.getDefaultExpressionName();
    return info;
}

static std::vector<PropertyInfo> getForeignPropertyInfos(TableCatalogEntry* entry) {
    std::vector<PropertyInfo> infos;
    for (auto& def : entry->getProperties()) {
        auto info = getInfo(def);
        info.propertyID = entry->getPropertyID(def.getName());
        infos.push_back(std::move(info));
    }
    return infos;
}

static std::vector<PropertyInfo> getNodePropertyInfos(NodeTableCatalogEntry* entry) {
    std::vector<PropertyInfo> infos;
    auto primaryKeyName = entry->getPrimaryKeyName();
    for (auto& def : entry->getProperties()) {
        auto info = getInfo(def);
        info.propertyID = entry->getPropertyID(def.getName());
        info.extraInfo = std::make_unique<ExtraNodePropertyInfo>(primaryKeyName == def.getName());
        infos.push_back(std::move(info));
    }
    return infos;
}

static std::vector<PropertyInfo> getRelPropertyInfos(RelGroupCatalogEntry* entry) {
    std::vector<PropertyInfo> infos;
    for (auto& def : entry->getProperties()) {
        if (def.getName() == InternalKeyword::ID) {
            continue;
        }
        auto info = getInfo(def);
        info.propertyID = entry->getPropertyID(def.getName());
        info.extraInfo = std::make_unique<ExtraRelPropertyInfo>(
            ExtendDirectionUtil::toString(entry->getStorageDirection()));
        infos.push_back(std::move(info));
    }
    return infos;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("property id");
    columnTypes.push_back(LogicalType::INT32());
    columnNames.emplace_back("name");
    columnTypes.push_back(LogicalType::STRING());
    columnNames.emplace_back("type");
    columnTypes.push_back(LogicalType::STRING());
    columnNames.emplace_back("default expression");
    columnTypes.push_back(LogicalType::STRING());
    auto name = common::StringUtils::split(input->getLiteralVal<std::string>(0), ".");

    std::vector<PropertyInfo> infos;
    CatalogEntryType type = CatalogEntryType::DUMMY_ENTRY;
    auto transaction = transaction::Transaction::Get(*context);
    if (name.size() == 1) {
        auto tableName = name[0];
        auto catalog = Catalog::Get(*context);
        if (catalog->containsTable(transaction, tableName)) {
            auto entry = catalog->getTableCatalogEntry(transaction, tableName);
            switch (entry->getType()) {
            case CatalogEntryType::NODE_TABLE_ENTRY: {
                columnNames.emplace_back("primary key");
                columnTypes.push_back(LogicalType::BOOL());
                infos = getNodePropertyInfos(entry->ptrCast<NodeTableCatalogEntry>());
                type = CatalogEntryType::NODE_TABLE_ENTRY;
            } break;
            case CatalogEntryType::REL_GROUP_ENTRY: {
                columnNames.emplace_back("storage_direction");
                columnTypes.push_back(LogicalType::STRING());
                infos = getRelPropertyInfos(entry->ptrCast<RelGroupCatalogEntry>());
                type = CatalogEntryType::REL_GROUP_ENTRY;
            } break;
            default:
                KU_UNREACHABLE;
            }
        } else {
            throw CatalogException(stringFormat("{} does not exist in catalog.", tableName));
        }
    } else {
        auto dbName = name[0];
        auto tableName = name[1];
        auto db = main::DatabaseManager::Get(*context)->getAttachedDatabase(dbName);
        auto entry = db->getCatalog()->getTableCatalogEntry(transaction, tableName);
        infos = getForeignPropertyInfos(entry);
        type = CatalogEntryType::FOREIGN_TABLE_ENTRY;
    }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<TableInfoBindData>(type, std::move(infos), columns);
}

function_set TableInfoFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<TableFunction>(name, std::vector{LogicalTypeID::STRING});
    function->tableFunc = SimpleTableFunc::getTableFunc(internalTableFunc);
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = SimpleTableFunc::initSharedState;
    function->initLocalStateFunc = TableFunction::initEmptyLocalState;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
