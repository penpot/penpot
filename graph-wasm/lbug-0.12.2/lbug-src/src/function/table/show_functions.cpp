#include "binder/binder.h"
#include "catalog/catalog.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace function {

struct FunctionInfo {
    std::string name;
    std::string type;
    std::string signature;

    FunctionInfo(std::string name, std::string type, std::string signature)
        : name{std::move(name)}, type{std::move(type)}, signature{std::move(signature)} {}
};

struct ShowFunctionsBindData final : TableFuncBindData {
    std::vector<FunctionInfo> sequences;

    ShowFunctionsBindData(std::vector<FunctionInfo> sequences, binder::expression_vector columns,
        offset_t maxOffset)
        : TableFuncBindData{std::move(columns), maxOffset}, sequences{std::move(sequences)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowFunctionsBindData>(sequences, columns, numRows);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto sequences = input.bindData->constPtrCast<ShowFunctionsBindData>()->sequences;
    auto numSequencesToOutput = morsel.getMorselSize();
    for (auto i = 0u; i < numSequencesToOutput; i++) {
        const auto functionInfo = sequences[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, functionInfo.name);
        output.getValueVectorMutable(1).setValue(i, functionInfo.type);
        output.getValueVectorMutable(2).setValue(i, functionInfo.signature);
    }
    return numSequencesToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("type");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("signature");
    columnTypes.emplace_back(LogicalType::STRING());
    std::vector<FunctionInfo> FunctionInfos;
    for (const auto& entry :
        Catalog::Get(*context)->getFunctionEntries(transaction::Transaction::Get(*context))) {
        const auto& functionSet = entry->getFunctionSet();
        const auto type = FunctionEntryTypeUtils::toString(entry->getType());
        for (auto& function : functionSet) {
            auto signature = function->signatureToString();
            FunctionInfos.emplace_back(entry->getName(), type, signature);
        }
    }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<ShowFunctionsBindData>(std::move(FunctionInfos), columns,
        FunctionInfos.size());
}

function_set ShowFunctionsFunction::getFunctionSet() {
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
