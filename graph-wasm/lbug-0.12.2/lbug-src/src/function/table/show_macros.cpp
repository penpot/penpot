#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "function/table/bind_data.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace function {

struct MacroInfo {
    std::string name;
    std::string definition;

    MacroInfo(std::string name, std::string definition)
        : name{std::move(name)}, definition(std::move(definition)) {}
};

struct ShowMacrosBindData final : TableFuncBindData {
    std::vector<MacroInfo> macros;

    ShowMacrosBindData(std::vector<MacroInfo> macros, binder::expression_vector columns,
        row_idx_t numRows)
        : TableFuncBindData{std::move(columns), numRows}, macros{std::move(macros)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowMacrosBindData>(macros, columns, numRows);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    const auto macros = input.bindData->constPtrCast<ShowMacrosBindData>()->macros;
    const auto numMacrosToOutput = morsel.endOffset - morsel.startOffset;
    for (auto i = 0u; i < numMacrosToOutput; i++) {
        const auto tableInfo = macros[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, tableInfo.name);
        output.getValueVectorMutable(1).setValue(i, tableInfo.definition);
    }
    return numMacrosToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("definition");
    columnTypes.emplace_back(LogicalType::STRING());
    std::vector<MacroInfo> macroInfos;
    auto transaction = transaction::Transaction::Get(*context);
    auto catalog = Catalog::Get(*context);
    for (auto& entry : catalog->getMacroEntries(transaction)) {
        std::string name = entry->getName();
        auto macroFunction = catalog->getScalarMacroFunction(transaction, name);
        macroInfos.emplace_back(name, macroFunction->toCypher(name));
    }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<ShowMacrosBindData>(std::move(macroInfos), std::move(columns),
        macroInfos.size());
}

function_set ShowMacrosFunction::getFunctionSet() {
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
