#include "binder/binder.h"
#include "extension/extension.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

static constexpr std::pair<std::string_view, std::string_view> extensions[] = {
    {"ALGO", "Adds support for graph algorithms"},
    {"AZURE", "Adds support for reading from azure blob storage"},
    {"DELTA", "Adds support for reading from delta tables"},
    {"DUCKDB", "Adds support for reading from duckdb tables"},
    {"FTS", "Adds support for full-text search indexes"},
    {"HTTPFS", "Adds support for reading and writing files over a HTTP(S)/S3 filesystem"},
    {"ICEBERG", "Adds support for reading from iceberg tables"},
    {"JSON", "Adds support for JSON operations"}, {"LLM", "Adds support for LLM operations"},
    {"NEO4J", "Adds support for migrating nodes and rels from neo4j to lbug"},
    {"POSTGRES", "Adds support for reading from POSTGRES tables"},
    {"SQLITE", "Adds support for reading from SQLITE tables"},
    {"UNITY_CATALOG", "Adds support for scanning delta tables registered in unity catalog"}};
static constexpr auto officialExtensions = std::to_array(extensions);

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& /*input*/,
    DataChunk& output) {
    auto numTuplesToOutput = morsel.getMorselSize();
    for (auto i = 0u; i < numTuplesToOutput; ++i) {
        auto& [name, description] = officialExtensions[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, name);
        output.getValueVectorMutable(1).setValue(i, description);
    }
    return numTuplesToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* /*context*/,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("description");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<TableFuncBindData>(std::move(columns), officialExtensions.size());
}

function_set ShowOfficialExtensionsFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<TableFunction>(name, std::vector<common::LogicalTypeID>{});
    function->tableFunc = SimpleTableFunc::getTableFunc(internalTableFunc);
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = SimpleTableFunc::initSharedState;
    function->initLocalStateFunc = TableFunction::initEmptyLocalState;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
