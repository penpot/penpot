#include "common/exception/binder.h"
#include "common/types/value/nested.h"
#include "function/gds/gds.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/standalone_call_function.h"
#include "graph/graph_entry_set.h"
#include "parser/parser.h"
#include "processor/execution_context.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::graph;

namespace lbug {
namespace function {

struct ProjectGraphNativeBindData final : TableFuncBindData {
    std::string graphName;
    std::vector<ParsedNativeGraphTableInfo> nodeInfos;
    std::vector<ParsedNativeGraphTableInfo> relInfos;

    ProjectGraphNativeBindData(std::string graphName,
        std::vector<ParsedNativeGraphTableInfo> nodeInfos,
        std::vector<ParsedNativeGraphTableInfo> relInfos)
        : TableFuncBindData{0}, graphName{std::move(graphName)}, nodeInfos{std::move(nodeInfos)},
          relInfos{std::move(relInfos)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ProjectGraphNativeBindData>(graphName, nodeInfos, relInfos);
    }
};

static offset_t tableFunc(const TableFuncInput& input, TableFuncOutput&) {
    const auto bindData = ku_dynamic_cast<ProjectGraphNativeBindData*>(input.bindData);
    auto graphEntrySet = GraphEntrySet::Get(*input.context->clientContext);
    graphEntrySet->validateGraphNotExist(bindData->graphName);
    auto entry = std::make_unique<ParsedNativeGraphEntry>(bindData->nodeInfos, bindData->relInfos);
    // bind graph entry to check if input is valid or not. Ignore bind result.
    GDSFunction::bindGraphEntry(*input.context->clientContext, *entry);
    graphEntrySet->addGraph(bindData->graphName, std::move(entry));
    return 0;
}

static std::string getStringVal(const Value& value) {
    value.validateType(LogicalTypeID::STRING);
    return value.getValue<std::string>();
}

static std::vector<ParsedNativeGraphTableInfo> extractGraphEntryTableInfos(const Value& value) {
    std::vector<ParsedNativeGraphTableInfo> infos;
    switch (value.getDataType().getLogicalTypeID()) {
    case LogicalTypeID::LIST: {
        for (auto i = 0u; i < NestedVal::getChildrenSize(&value); ++i) {
            auto tableName = getStringVal(*NestedVal::getChildVal(&value, i));
            infos.emplace_back(tableName, "" /* empty predicate */);
        }
    } break;
    case LogicalTypeID::STRUCT: {
        for (auto i = 0u; i < StructType::getNumFields(value.getDataType()); ++i) {
            auto& field = StructType::getField(value.getDataType(), i);
            auto tableName = field.getName();
            auto predicate = getStringVal(*NestedVal::getChildVal(&value, i));
            infos.emplace_back(tableName, predicate);
        }
    } break;
    default:
        throw BinderException(
            stringFormat("Argument {} has data type {}. LIST or STRUCT was expected.",
                value.toString(), value.getDataType().toString()));
    }
    return infos;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext*,
    const TableFuncBindInput* input) {
    auto graphName = input->getLiteralVal<std::string>(0);
    auto nodeInfos = extractGraphEntryTableInfos(input->getValue(1));
    auto relInfos = extractGraphEntryTableInfos(input->getValue(2));
    return std::make_unique<ProjectGraphNativeBindData>(graphName, nodeInfos, relInfos);
}

function_set ProjectGraphNativeFunction::getFunctionSet() {
    function_set functionSet;
    auto func = std::make_unique<TableFunction>(name,
        std::vector{LogicalTypeID::STRING, LogicalTypeID::ANY, LogicalTypeID::ANY});
    func->bindFunc = bindFunc;
    func->tableFunc = tableFunc;
    func->initSharedStateFunc = TableFunction::initEmptySharedState;
    func->initLocalStateFunc = TableFunction::initEmptyLocalState;
    func->canParallelFunc = []() { return false; };
    functionSet.push_back(std::move(func));
    return functionSet;
}

} // namespace function
} // namespace lbug
