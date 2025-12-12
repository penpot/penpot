#include "processor/operator/table_function_call.h"
#include "processor/operator/table_scan/ftable_scan_function.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::binder;
using namespace lbug::function;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::createFTableScan(const expression_vector& exprs,
    std::vector<ft_col_idx_t> colIndices, const Schema* schema,
    std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize, physical_op_vector_t children) {
    std::vector<DataPos> outPosV;
    if (!exprs.empty()) {
        KU_ASSERT(schema);
        outPosV = getDataPos(exprs, *schema);
    }
    auto function = FTableScan::getFunction();
    auto bindData =
        std::make_unique<FTableScanBindData>(table, std::move(colIndices), maxMorselSize);
    auto info = TableFunctionCallInfo();
    info.function = *function->copy();
    info.bindData = std::move(bindData);
    info.outPosV = std::move(outPosV);
    auto initInput = TableFuncInitSharedStateInput(info.bindData.get(), executionContext);
    auto sharedState = info.function.initSharedStateFunc(initInput);
    auto printInfo = std::make_unique<TableFunctionCallPrintInfo>(function->name, exprs);
    auto result = std::make_unique<TableFunctionCall>(std::move(info), sharedState, getOperatorID(),
        std::move(printInfo));
    for (auto& child : children) {
        result->addChild(std::move(child));
    }
    return result;
}

std::unique_ptr<PhysicalOperator> PlanMapper::createFTableScan(const expression_vector& exprs,
    const std::vector<ft_col_idx_t>& colIndices, const Schema* schema,
    std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize) {
    physical_op_vector_t children;
    return createFTableScan(exprs, colIndices, schema, std::move(table), maxMorselSize,
        std::move(children));
}

std::unique_ptr<PhysicalOperator> PlanMapper::createEmptyFTableScan(
    std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize, physical_op_vector_t children) {
    return createFTableScan(expression_vector{}, std::vector<ft_col_idx_t>{}, nullptr /* schema */,
        std::move(table), maxMorselSize, std::move(children));
}

std::unique_ptr<PhysicalOperator> PlanMapper::createEmptyFTableScan(
    std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize,
    std::unique_ptr<PhysicalOperator> child) {
    physical_op_vector_t children;
    children.push_back(std::move(child));
    return createFTableScan(expression_vector{}, std::vector<ft_col_idx_t>{}, nullptr /* schema */,
        std::move(table), maxMorselSize, std::move(children));
}

std::unique_ptr<PhysicalOperator> PlanMapper::createEmptyFTableScan(
    std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize) {
    physical_op_vector_t children;
    return createFTableScan(expression_vector{}, std::vector<ft_col_idx_t>{}, nullptr /* schema */,
        std::move(table), maxMorselSize, std::move(children));
}

std::unique_ptr<PhysicalOperator> PlanMapper::createFTableScanAligned(
    const expression_vector& exprs, const Schema* schema, std::shared_ptr<FactorizedTable> table,
    uint64_t maxMorselSize, physical_op_vector_t children) {
    std::vector<ft_col_idx_t> colIndices;
    colIndices.reserve(exprs.size());
    for (auto i = 0u; i < exprs.size(); ++i) {
        colIndices.push_back(i);
    }
    return createFTableScan(exprs, std::move(colIndices), schema, std::move(table), maxMorselSize,
        std::move(children));
}

std::unique_ptr<PhysicalOperator> PlanMapper::createFTableScanAligned(
    const expression_vector& exprs, const Schema* schema, std::shared_ptr<FactorizedTable> table,
    uint64_t maxMorselSize) {
    physical_op_vector_t children;
    return createFTableScanAligned(exprs, schema, std::move(table), maxMorselSize,
        std::move(children));
}

} // namespace processor
} // namespace lbug
