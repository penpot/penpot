#include "binder/binder.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

static offset_t internalTableFunc(const TableFuncMorsel& /*morsel*/,
    const TableFuncInput& /*input*/, DataChunk& output) {
    auto& outputVector = output.getValueVectorMutable(0);
    auto pos = output.state->getSelVector()[0];
    outputVector.setValue(pos, std::string(LBUG_VERSION));
    return 1;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext*,
    const TableFuncBindInput* input) {
    std::vector<std::string> returnColumnNames;
    std::vector<LogicalType> returnTypes;
    returnColumnNames.emplace_back("version");
    returnTypes.emplace_back(LogicalType::STRING());
    returnColumnNames =
        TableFunction::extractYieldVariables(returnColumnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(returnColumnNames, returnTypes);
    return std::make_unique<TableFuncBindData>(std::move(columns), 1 /* one row result */);
}

function_set DBVersionFunction::getFunctionSet() {
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
