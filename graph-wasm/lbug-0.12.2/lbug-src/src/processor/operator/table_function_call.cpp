#include "processor/operator/table_function_call.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace processor {

std::string TableFunctionCallPrintInfo::toString() const {
    std::string result = "Function: ";
    result += funcName;
    if (!exprs.empty()) {
        result += ", Expressions: ";
        result += binder::ExpressionUtil::toString(exprs);
    }
    return result;
}

void TableFunctionCall::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    auto initLocalStateInput =
        TableFuncInitLocalStateInput(*sharedState, *info.bindData, context->clientContext);
    localState = info.function.initLocalStateFunc(initLocalStateInput);
    funcInput = std::make_unique<TableFuncInput>(info.bindData.get(), localState.get(),
        sharedState.get(), context);
    auto initOutputInput = TableFuncInitOutputInput(info.outPosV, *resultSet);
    // Technically we should make all table function has its own initOutputFunc. But since most
    // table function is using initSingleDataChunkScanOutput. For simplicity, we assume if no
    // initOutputFunc provided then we use to initSingleDataChunkScanOutput.
    if (info.function.initOutputFunc == nullptr) {
        funcOutput = TableFunction::initSingleDataChunkScanOutput(initOutputInput);
    } else {
        funcOutput = info.function.initOutputFunc(initOutputInput);
    }
}

bool TableFunctionCall::getNextTuplesInternal(ExecutionContext* context) {
    funcOutput->resetState();
    funcInput->bindData->evaluateParams(context->clientContext);
    auto numTuplesScanned = info.function.tableFunc(*funcInput, *funcOutput);
    funcOutput->setOutputSize(numTuplesScanned);
    metrics->numOutputTuple.increase(numTuplesScanned);
    return numTuplesScanned != 0;
}

void TableFunctionCall::finalizeInternal(ExecutionContext* context) {
    info.function.finalizeFunc(context, sharedState.get());
}

double TableFunctionCall::getProgress(ExecutionContext* /*context*/) const {
    return info.function.progressFunc(sharedState.get());
}

} // namespace processor
} // namespace lbug
