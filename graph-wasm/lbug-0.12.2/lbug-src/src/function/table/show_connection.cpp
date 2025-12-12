#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/exception/binder.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct ShowConnectionBindData final : TableFuncBindData {
    std::vector<std::pair<NodeTableCatalogEntry*, NodeTableCatalogEntry*>> srcDstEntries;

    ShowConnectionBindData(
        std::vector<std::pair<NodeTableCatalogEntry*, NodeTableCatalogEntry*>> srcDstEntries,
        binder::expression_vector columns, offset_t maxOffset)
        : TableFuncBindData{std::move(columns), maxOffset},
          srcDstEntries{std::move(srcDstEntries)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowConnectionBindData>(srcDstEntries, columns, numRows);
    }
};

static void outputRelTableConnection(DataChunk& outputDataChunk, uint64_t outputPos,
    const NodeTableCatalogEntry& srcEntry, const NodeTableCatalogEntry& dstEntry) {
    // Write result to dataChunk
    outputDataChunk.getValueVectorMutable(0).setValue(outputPos, srcEntry.getName());
    outputDataChunk.getValueVectorMutable(1).setValue(outputPos, dstEntry.getName());
    outputDataChunk.getValueVectorMutable(2).setValue(outputPos, srcEntry.getPrimaryKeyName());
    outputDataChunk.getValueVectorMutable(3).setValue(outputPos, dstEntry.getPrimaryKeyName());
}

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    const auto bindData = input.bindData->constPtrCast<ShowConnectionBindData>();
    auto i = 0u;
    auto size = morsel.getMorselSize();
    for (; i < size; i++) {
        auto [srcEntry, dstEntry] = bindData->srcDstEntries[i + morsel.startOffset];
        outputRelTableConnection(output, i, *srcEntry, *dstEntry);
    }
    return i;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("source table name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("destination table name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("source table primary key");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("destination table primary key");
    columnTypes.emplace_back(LogicalType::STRING());
    const auto name = input->getLiteralVal<std::string>(0);
    const auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    std::vector<std::pair<NodeTableCatalogEntry*, NodeTableCatalogEntry*>> srcDstEntries;
    if (catalog->containsTable(transaction, name)) {
        auto entry = catalog->getTableCatalogEntry(transaction, name);
        if (entry->getType() != catalog::CatalogEntryType::REL_GROUP_ENTRY) {
            throw BinderException{"Show connection can only be called on a rel table!"};
        }
        for (auto& info : entry->ptrCast<RelGroupCatalogEntry>()->getRelEntryInfos()) {
            auto srcEntry = catalog->getTableCatalogEntry(transaction, info.nodePair.srcTableID)
                                ->ptrCast<NodeTableCatalogEntry>();
            auto dstEntry = catalog->getTableCatalogEntry(transaction, info.nodePair.dstTableID)
                                ->ptrCast<NodeTableCatalogEntry>();
            srcDstEntries.emplace_back(srcEntry, dstEntry);
        }
    } else {
        throw BinderException{"Show connection can only be called on a rel table!"};
    }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<ShowConnectionBindData>(srcDstEntries, columns, srcDstEntries.size());
}

function_set ShowConnectionFunction::getFunctionSet() {
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
