#include "binder/binder.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/exception/binder.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct StatsInfoBindData final : TableFuncBindData {
    TableCatalogEntry* tableEntry;
    storage::Table* table;
    const ClientContext* context;

    StatsInfoBindData(binder::expression_vector columns, TableCatalogEntry* tableEntry,
        storage::Table* table, const ClientContext* context)
        : TableFuncBindData{std::move(columns), 1 /*numRows*/}, tableEntry{tableEntry},
          table{table}, context{context} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<StatsInfoBindData>(columns, tableEntry, table, context);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& /*morsel*/, const TableFuncInput& input,
    DataChunk& output) {
    const auto bindData = input.bindData->constPtrCast<StatsInfoBindData>();
    const auto table = bindData->table;
    switch (table->getTableType()) {
    case TableType::NODE: {
        const auto& nodeTable = table->cast<storage::NodeTable>();
        const auto stats = nodeTable.getStats(transaction::Transaction::Get(*bindData->context));
        output.getValueVectorMutable(0).setValue<cardinality_t>(0, stats.getTableCard());
        for (auto i = 0u; i < nodeTable.getNumColumns(); ++i) {
            output.getValueVectorMutable(i + 1).setValue(0, stats.getNumDistinctValues(i));
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    return 1;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext* context,
    const TableFuncBindInput* input) {
    const auto tableName = input->getLiteralVal<std::string>(0);
    const auto catalog = Catalog::Get(*context);
    if (!catalog->containsTable(transaction::Transaction::Get(*context), tableName)) {
        throw BinderException{"Table " + tableName + " does not exist!"};
    }
    auto tableEntry =
        catalog->getTableCatalogEntry(transaction::Transaction::Get(*context), tableName);
    if (tableEntry->getTableType() != TableType::NODE) {
        throw BinderException{
            "Stats from a non-node table " + tableName + " is not supported yet!"};
    }

    std::vector<std::string> columnNames = {"cardinality"};
    std::vector<LogicalType> columnTypes;
    columnTypes.push_back(LogicalType::INT64());
    for (auto& propDef : tableEntry->getProperties()) {
        columnNames.push_back(propDef.getName() + "_distinct_count");
        columnTypes.push_back(LogicalType::INT64());
    }
    const auto storageManager = storage::StorageManager::Get(*context);
    auto table = storageManager->getTable(tableEntry->getTableID());
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<StatsInfoBindData>(columns, tableEntry, table, context);
}

function_set StatsInfoFunction::getFunctionSet() {
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
