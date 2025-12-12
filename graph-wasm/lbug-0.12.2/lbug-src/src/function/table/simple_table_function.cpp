#include "function/table/simple_table_function.h"

#include "function/table/bind_data.h"

namespace lbug {
namespace function {

TableFuncMorsel SimpleTableFuncSharedState::getMorsel() {
    std::lock_guard lck{mtx};
    KU_ASSERT(curRowIdx <= numRows);
    if (curRowIdx == numRows) {
        return TableFuncMorsel::createInvalidMorsel();
    }
    const auto numValuesToOutput = std::min(maxMorselSize, numRows - curRowIdx);
    curRowIdx += numValuesToOutput;
    return {curRowIdx - numValuesToOutput, curRowIdx};
}

std::unique_ptr<TableFuncSharedState> SimpleTableFunc::initSharedState(
    const TableFuncInitSharedStateInput& input) {
    return std::make_unique<SimpleTableFuncSharedState>(input.bindData->numRows);
}

common::offset_t tableFunc(simple_internal_table_func internalTableFunc,
    const TableFuncInput& input, TableFuncOutput& output) {
    const auto sharedState = input.sharedState->ptrCast<SimpleTableFuncSharedState>();
    auto morsel = sharedState->getMorsel();
    if (!morsel.hasMoreToOutput()) {
        return 0;
    }
    return internalTableFunc(morsel, input, output.dataChunk);
}

table_func_t SimpleTableFunc::getTableFunc(simple_internal_table_func internalTableFunc) {
    return std::bind(tableFunc, internalTableFunc, std::placeholders::_1, std::placeholders::_2);
}

} // namespace function
} // namespace lbug
