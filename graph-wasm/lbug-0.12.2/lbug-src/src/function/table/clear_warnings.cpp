#include "function/table/bind_data.h"
#include "function/table/standalone_call_function.h"
#include "function/table/table_function.h"
#include "processor/execution_context.h"
#include "processor/warning_context.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static offset_t tableFunc(const TableFuncInput& input, TableFuncOutput&) {
    auto warningContext = processor::WarningContext::Get(*input.context->clientContext);
    warningContext->clearPopulatedWarnings();
    return 0;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext*,
    const TableFuncBindInput*) {
    return std::make_unique<TableFuncBindData>(0);
}

function_set ClearWarningsFunction::getFunctionSet() {
    function_set functionSet;
    auto func = std::make_unique<TableFunction>(name, std::vector<LogicalTypeID>{});
    func->tableFunc = tableFunc;
    func->bindFunc = bindFunc;
    func->initSharedStateFunc = TableFunction::initEmptySharedState;
    func->initLocalStateFunc = TableFunction::initEmptyLocalState;
    func->canParallelFunc = []() { return false; };
    func->isReadOnly = false;
    functionSet.push_back(std::move(func));
    return functionSet;
}

} // namespace function
} // namespace lbug
