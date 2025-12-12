#include "binder/binder.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "graph/graph_entry_set.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct ProjectedGraphData {
    std::string name;
    std::string type;

    ProjectedGraphData(std::string name, std::string type)
        : name{std::move(name)}, type{std::move(type)} {}
};

struct ShowProjectedGraphBindData : public TableFuncBindData {
    std::vector<ProjectedGraphData> projectedGraphData;

    ShowProjectedGraphBindData(std::vector<ProjectedGraphData> projectedGraphData,
        binder::expression_vector columns)
        : TableFuncBindData{std::move(columns), projectedGraphData.size()},
          projectedGraphData{std::move(projectedGraphData)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowProjectedGraphBindData>(projectedGraphData, columns);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto& projectedGraphData =
        input.bindData->constPtrCast<ShowProjectedGraphBindData>()->projectedGraphData;
    auto numTablesToOutput = morsel.endOffset - morsel.startOffset;
    for (auto i = 0u; i < numTablesToOutput; i++) {
        auto graphData = projectedGraphData[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, graphData.name);
        output.getValueVectorMutable(1).setValue(i, graphData.type);
    }
    return numTablesToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> returnColumnNames;
    std::vector<LogicalType> returnTypes;
    returnColumnNames.emplace_back("name");
    returnTypes.emplace_back(LogicalType::STRING());
    returnColumnNames.emplace_back("type");
    returnTypes.emplace_back(LogicalType::STRING());
    returnColumnNames =
        TableFunction::extractYieldVariables(returnColumnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(returnColumnNames, returnTypes);
    std::vector<ProjectedGraphData> projectedGraphData;
    for (auto& [name, entry] : graph::GraphEntrySet::Get(*context)->getNameToEntryMap()) {
        projectedGraphData.emplace_back(name, graph::GraphEntryTypeUtils::toString(entry->type));
    }
    return std::make_unique<ShowProjectedGraphBindData>(std::move(projectedGraphData),
        std::move(columns));
}

function_set ShowProjectedGraphsFunction::getFunctionSet() {
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
