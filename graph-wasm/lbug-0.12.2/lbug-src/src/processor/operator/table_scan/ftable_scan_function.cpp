#include "processor/operator/table_scan/ftable_scan_function.h"

#include "function/table/simple_table_function.h"
#include "processor/result/factorized_table.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace processor {

struct FTableScanSharedState final : public SimpleTableFuncSharedState {
    std::shared_ptr<FactorizedTable> table;
    uint64_t morselSize;
    offset_t nextTupleIdx;

    FTableScanSharedState(std::shared_ptr<FactorizedTable> table, uint64_t morselSize)
        : SimpleTableFuncSharedState{table->getNumTuples()}, table{std::move(table)},
          morselSize{morselSize}, nextTupleIdx{0} {}

    TableFuncMorsel getMorsel() override {
        std::unique_lock lck{mtx};
        auto numTuplesToScan = std::min(morselSize, table->getNumTuples() - nextTupleIdx);
        auto morsel = TableFuncMorsel(nextTupleIdx, nextTupleIdx + numTuplesToScan);
        nextTupleIdx += numTuplesToScan;
        return morsel;
    }
};

// FTableScan has an exceptional output where vectors can be in different dataChunks. So we give
// a dummy dataChunk during initialization and never use it.
struct FTableScanTableFuncOutput : TableFuncOutput {
    std::vector<common::ValueVector*> vectors;

    explicit FTableScanTableFuncOutput(std::vector<common::ValueVector*> vectors)
        : TableFuncOutput(common::DataChunk{} /* dummy DataChunk */), vectors{std::move(vectors)} {}
};

static std::unique_ptr<TableFuncOutput> initFTableScanOutput(
    const TableFuncInitOutputInput& input) {
    std::vector<ValueVector*> vectors;
    for (auto i = 0u; i < input.outColumnPositions.size(); ++i) {
        vectors.push_back(input.resultSet.getValueVector(input.outColumnPositions[i]).get());
    }
    return std::make_unique<FTableScanTableFuncOutput>(std::move(vectors));
}

static offset_t tableFunc(const TableFuncInput& input, TableFuncOutput& output) {
    auto sharedState = ku_dynamic_cast<FTableScanSharedState*>(input.sharedState);
    auto bindData = ku_dynamic_cast<FTableScanBindData*>(input.bindData);
    auto morsel = sharedState->getMorsel();
    if (morsel.endOffset <= morsel.startOffset) {
        return 0;
    }
    auto numTuples = morsel.endOffset - morsel.startOffset;
    auto& output_ = ku_dynamic_cast<FTableScanTableFuncOutput&>(output);
    sharedState->table->scan(output_.vectors, morsel.startOffset, numTuples,
        bindData->columnIndices);
    return numTuples;
}

static std::unique_ptr<TableFuncSharedState> initSharedState(
    const TableFuncInitSharedStateInput& input) {
    auto bindData = ku_dynamic_cast<FTableScanBindData*>(input.bindData);
    return std::make_unique<FTableScanSharedState>(bindData->table, bindData->morselSize);
}

std::unique_ptr<TableFunction> FTableScan::getFunction() {
    auto function = std::make_unique<TableFunction>(name, std::vector<LogicalTypeID>{});
    function->tableFunc = tableFunc;
    function->initSharedStateFunc = initSharedState;
    function->initLocalStateFunc = TableFunction::initEmptyLocalState;
    function->initOutputFunc = initFTableScanOutput;
    return function;
}

} // namespace processor
} // namespace lbug
