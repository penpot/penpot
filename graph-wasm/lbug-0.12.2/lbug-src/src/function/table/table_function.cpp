#include "function/table/table_function.h"

#include "common/exception/binder.h"
#include "parser/query/reading_clause/yield_variable.h"
#include "planner/operator/logical_table_function_call.h"
#include "planner/planner.h"
#include "processor/data_pos.h"
#include "processor/operator/table_function_call.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::processor;

namespace lbug {
namespace function {

void TableFuncOutput::resetState() {
    dataChunk.state->getSelVectorUnsafe().setSelSize(0);
    dataChunk.resetAuxiliaryBuffer();
    for (auto i = 0u; i < dataChunk.getNumValueVectors(); i++) {
        dataChunk.getValueVectorMutable(i).setAllNonNull();
    }
}

void TableFuncOutput::setOutputSize(offset_t size) const {
    dataChunk.state->getSelVectorUnsafe().setToUnfiltered(size);
}

TableFunction::~TableFunction() = default;

std::unique_ptr<TableFuncLocalState> TableFunction::initEmptyLocalState(
    const TableFuncInitLocalStateInput&) {
    return std::make_unique<TableFuncLocalState>();
}

std::unique_ptr<TableFuncSharedState> TableFunction::initEmptySharedState(
    const TableFuncInitSharedStateInput& /*input*/) {
    return std::make_unique<TableFuncSharedState>();
}

std::unique_ptr<TableFuncOutput> TableFunction::initSingleDataChunkScanOutput(
    const TableFuncInitOutputInput& input) {
    if (input.outColumnPositions.empty()) {
        return std::make_unique<TableFuncOutput>(DataChunk{});
    }
    auto state = input.resultSet.getDataChunk(input.outColumnPositions[0].dataChunkPos)->state;
    auto dataChunk = DataChunk(input.outColumnPositions.size(), state);
    for (auto i = 0u; i < input.outColumnPositions.size(); ++i) {
        dataChunk.insert(i, input.resultSet.getValueVector(input.outColumnPositions[i]));
    }
    return std::make_unique<TableFuncOutput>(std::move(dataChunk));
}

std::vector<std::string> TableFunction::extractYieldVariables(const std::vector<std::string>& names,
    const std::vector<parser::YieldVariable>& yieldVariables) {
    std::vector<std::string> variableNames;
    if (!yieldVariables.empty()) {
        if (yieldVariables.size() < names.size()) {
            throw BinderException{"Output variables must all appear in the yield clause."};
        }
        if (yieldVariables.size() > names.size()) {
            throw BinderException{"The number of variables in the yield clause exceeds the "
                                  "number of output variables of the table function."};
        }
        for (auto i = 0u; i < names.size(); i++) {
            if (names[i] != yieldVariables[i].name) {
                throw BinderException{stringFormat(
                    "Unknown table function output variable name: {}.", yieldVariables[i].name)};
            }
            auto variableName =
                yieldVariables[i].hasAlias() ? yieldVariables[i].alias : yieldVariables[i].name;
            variableNames.push_back(variableName);
        }
    } else {
        variableNames = names;
    }
    return variableNames;
}

void TableFunction::getLogicalPlan(Planner* planner,
    const binder::BoundReadingClause& boundReadingClause, binder::expression_vector predicates,
    LogicalPlan& plan) {
    auto op = planner->getTableFunctionCall(boundReadingClause);
    planner->planReadOp(op, predicates, plan);
}

std::unique_ptr<PhysicalOperator> TableFunction::getPhysicalPlan(PlanMapper* planMapper,
    const LogicalOperator* logicalOp) {
    std::vector<DataPos> outPosV;
    auto& call = logicalOp->constCast<LogicalTableFunctionCall>();
    auto outSchema = call.getSchema();
    for (auto& expr : call.getBindData()->columns) {
        outPosV.emplace_back(planMapper->getDataPos(*expr, *outSchema));
    }
    auto info = TableFunctionCallInfo();
    info.function = call.getTableFunc();
    info.bindData = call.getBindData()->copy();
    info.outPosV = outPosV;
    auto initInput =
        TableFuncInitSharedStateInput(info.bindData.get(), planMapper->executionContext);
    auto sharedState = info.function.initSharedStateFunc(initInput);
    auto printInfo = std::make_unique<TableFunctionCallPrintInfo>(call.getTableFunc().name,
        call.getBindData()->columns);
    return std::make_unique<TableFunctionCall>(std::move(info), sharedState,
        planMapper->getOperatorID(), std::move(printInfo));
}

offset_t TableFunction::emptyTableFunc(const TableFuncInput&, TableFuncOutput&) {
    // DO NOTHING.
    return 0;
}

} // namespace function
} // namespace lbug
