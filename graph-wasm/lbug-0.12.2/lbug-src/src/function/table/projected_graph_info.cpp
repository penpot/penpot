#include "binder/binder.h"
#include "common/exception/binder.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "graph/graph_entry_set.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct ProjectedGraphInfo {
    virtual ~ProjectedGraphInfo() = default;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

    virtual std::unique_ptr<ProjectedGraphInfo> copy() const = 0;
};

struct ProjectedTableInfo {
    std::string tableType;
    std::string tableName;
    std::string predicate;

    ProjectedTableInfo(std::string tableType, std::string tableName, std::string predicate)
        : tableType{std::move(tableType)}, tableName{std::move(tableName)},
          predicate{std::move(predicate)} {}
};

struct NativeProjectedGraphInfo final : ProjectedGraphInfo {
    std::vector<ProjectedTableInfo> tableInfo;

    explicit NativeProjectedGraphInfo(std::vector<ProjectedTableInfo> tableInfo)
        : tableInfo{std::move(tableInfo)} {}

    std::unique_ptr<ProjectedGraphInfo> copy() const override {
        return std::make_unique<NativeProjectedGraphInfo>(tableInfo);
    }
};

struct CypherProjectedGraphInfo final : ProjectedGraphInfo {
    std::string cypherQuery;

    explicit CypherProjectedGraphInfo(std::string cypherQuery)
        : cypherQuery{std::move(cypherQuery)} {}

    std::unique_ptr<ProjectedGraphInfo> copy() const override {
        return std::make_unique<CypherProjectedGraphInfo>(cypherQuery);
    }
};

struct ProjectedGraphInfoBindData final : TableFuncBindData {
    graph::GraphEntryType type;
    std::unique_ptr<ProjectedGraphInfo> info;

    ProjectedGraphInfoBindData(binder::expression_vector columns, graph::GraphEntryType type,
        std::unique_ptr<ProjectedGraphInfo> info)
        : TableFuncBindData{std::move(columns),
              type == graph::GraphEntryType::NATIVE ?
                  info->constCast<NativeProjectedGraphInfo>().tableInfo.size() :
                  1},
          type{type}, info{std::move(info)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ProjectedGraphInfoBindData>(columns, type, info->copy());
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto projectedGraphData = input.bindData->constPtrCast<ProjectedGraphInfoBindData>();
    switch (projectedGraphData->type) {
    case graph::GraphEntryType::NATIVE: {
        auto morselSize = morsel.getMorselSize();
        auto nativeProjectedGraphInfo =
            projectedGraphData->info->constCast<NativeProjectedGraphInfo>();
        for (auto i = 0u; i < morselSize; i++) {
            auto& tableInfo = nativeProjectedGraphInfo.tableInfo[i + morsel.startOffset];
            output.getValueVectorMutable(0).setValue(i, tableInfo.tableType);
            output.getValueVectorMutable(1).setValue(i, tableInfo.tableName);
            output.getValueVectorMutable(2).setValue(i, tableInfo.predicate);
        }
        return morselSize;
    }
    case graph::GraphEntryType::CYPHER: {
        output.getValueVectorMutable(0).setValue(0,
            projectedGraphData->info->constCast<CypherProjectedGraphInfo>().cypherQuery);
        return 1;
    }
    default:
        KU_UNREACHABLE;
    }
}

static std::unique_ptr<TableFuncBindData> bindFunc(const ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> returnColumnNames;
    std::vector<LogicalType> returnTypes;
    auto graphName = input->getValue(0).toString();
    auto graphEntrySet = graph::GraphEntrySet::Get(*context);
    if (!graphEntrySet->hasGraph(graphName)) {
        throw BinderException(stringFormat("Graph {} does not exist.", graphName));
    }
    auto graphEntry = graphEntrySet->getEntry(graphName);
    switch (graphEntry->type) {
    case graph::GraphEntryType::CYPHER: {
        returnColumnNames.emplace_back("cypher statement");
        returnTypes.emplace_back(LogicalType::STRING());
    } break;
    case graph::GraphEntryType::NATIVE: {
        returnColumnNames.emplace_back("table type");
        returnTypes.emplace_back(LogicalType::STRING());
        returnColumnNames.emplace_back("table name");
        returnTypes.emplace_back(LogicalType::STRING());
        returnColumnNames.emplace_back("predicate");
        returnTypes.emplace_back(LogicalType::STRING());
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    returnColumnNames =
        TableFunction::extractYieldVariables(returnColumnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(returnColumnNames, returnTypes);
    std::unique_ptr<ProjectedGraphInfo> projectedGraphInfo;
    switch (graphEntry->type) {
    case graph::GraphEntryType::CYPHER: {
        auto& cypherGraphEntry = graphEntry->cast<graph::ParsedCypherGraphEntry>();
        projectedGraphInfo =
            std::make_unique<CypherProjectedGraphInfo>(cypherGraphEntry.cypherQuery);
    } break;
    case graph::GraphEntryType::NATIVE: {
        auto& nativeGraphEntry = graphEntry->cast<graph::ParsedNativeGraphEntry>();
        std::vector<ProjectedTableInfo> tableInfo;
        for (auto& nodeInfo : nativeGraphEntry.nodeInfos) {
            tableInfo.emplace_back(TableTypeUtils::toString(TableType::NODE), nodeInfo.tableName,
                nodeInfo.predicate);
        }
        for (auto& relInfo : nativeGraphEntry.relInfos) {
            tableInfo.emplace_back(TableTypeUtils::toString(TableType::REL), relInfo.tableName,
                relInfo.predicate);
        }
        projectedGraphInfo = std::make_unique<NativeProjectedGraphInfo>(std::move(tableInfo));
    } break;
    default:
        KU_UNREACHABLE;
    }
    return std::make_unique<ProjectedGraphInfoBindData>(std::move(columns), graphEntry->type,
        std::move(projectedGraphInfo));
}

function_set ProjectedGraphInfoFunction::getFunctionSet() {
    function_set functionSet;
    auto function =
        std::make_unique<TableFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::STRING});
    function->tableFunc = SimpleTableFunc::getTableFunc(internalTableFunc);
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = SimpleTableFunc::initSharedState;
    function->initLocalStateFunc = TableFunction::initEmptyLocalState;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
