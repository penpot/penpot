#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "function/table/bind_data.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "main/database_manager.h"

using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace function {

struct TableInfo {
    std::string name;
    table_id_t id;
    std::string type;
    std::string databaseName;
    std::string comment;

    TableInfo(std::string name, table_id_t id, std::string type, std::string databaseName,
        std::string comment)
        : name{std::move(name)}, id{id}, type{std::move(type)},
          databaseName{std::move(databaseName)}, comment{std::move(comment)} {}
};

struct ShowTablesBindData final : TableFuncBindData {
    std::vector<TableInfo> tables;

    ShowTablesBindData(std::vector<TableInfo> tables, binder::expression_vector columns,
        row_idx_t numRows)
        : TableFuncBindData{std::move(columns), numRows}, tables{std::move(tables)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowTablesBindData>(tables, columns, numRows);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    const auto tables = input.bindData->constPtrCast<ShowTablesBindData>()->tables;
    const auto numTablesToOutput = morsel.endOffset - morsel.startOffset;
    for (auto i = 0u; i < numTablesToOutput; i++) {
        const auto tableInfo = tables[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, tableInfo.id);
        output.getValueVectorMutable(1).setValue(i, tableInfo.name);
        output.getValueVectorMutable(2).setValue(i, tableInfo.type);
        output.getValueVectorMutable(3).setValue(i, tableInfo.databaseName);
        output.getValueVectorMutable(4).setValue(i, tableInfo.comment);
    }
    return numTablesToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("id");
    columnTypes.emplace_back(LogicalType::UINT64());
    columnNames.emplace_back("name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("type");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("database name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("comment");
    columnTypes.emplace_back(LogicalType::STRING());
    std::vector<TableInfo> tableInfos;
    auto transaction = transaction::Transaction::Get(*context);
    if (!context->hasDefaultDatabase()) {
        auto catalog = Catalog::Get(*context);
        for (auto& entry :
            catalog->getTableEntries(transaction, context->useInternalCatalogEntry())) {
            tableInfos.emplace_back(entry->getName(), entry->getTableID(),
                TableTypeUtils::toString(entry->getTableType()), LOCAL_DB_NAME,
                entry->getComment());
        }
    }

    for (auto attachedDatabase : main::DatabaseManager::Get(*context)->getAttachedDatabases()) {
        auto databaseName = attachedDatabase->getDBName();
        auto databaseType = attachedDatabase->getDBType();
        for (auto& entry : attachedDatabase->getCatalog()->getTableEntries(transaction,
                 context->useInternalCatalogEntry())) {
            auto tableInfo = TableInfo{entry->getName(), entry->getTableID(),
                TableTypeUtils::toString(entry->getTableType()),
                stringFormat("{}({})", databaseName, databaseType), entry->getComment()};
            tableInfos.push_back(std::move(tableInfo));
        }
    }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<ShowTablesBindData>(std::move(tableInfos), std::move(columns),
        tableInfos.size());
}

function_set ShowTablesFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<TableFunction>(name, std::vector<LogicalTypeID>{});
    function->tableFunc = SimpleTableFunc::getTableFunc(internalTableFunc);
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = SimpleTableFunc::initSharedState;
    function->initLocalStateFunc = TableFunction::initEmptyLocalState;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
