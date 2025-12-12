#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/index_catalog_entry.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct IndexInfo {
    std::string tableName;
    std::string indexName;
    std::string indexType;
    std::vector<std::string> properties;
    bool dependencyLoaded;
    std::string indexDefinition;

    IndexInfo(std::string tableName, std::string indexName, std::string indexType,
        std::vector<std::string> properties, bool dependencyLoaded, std::string indexDefinition)
        : tableName{std::move(tableName)}, indexName{std::move(indexName)},
          indexType{std::move(indexType)}, properties{std::move(properties)},
          dependencyLoaded{dependencyLoaded}, indexDefinition{std::move(indexDefinition)} {}
};

struct ShowIndexesBindData final : TableFuncBindData {
    std::vector<IndexInfo> indexesInfo;

    ShowIndexesBindData(std::vector<IndexInfo> indexesInfo, binder::expression_vector columns,
        offset_t maxOffset)
        : TableFuncBindData{std::move(columns), maxOffset}, indexesInfo{std::move(indexesInfo)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowIndexesBindData>(*this);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto& indexesInfo = input.bindData->constPtrCast<ShowIndexesBindData>()->indexesInfo;
    auto numTuplesToOutput = morsel.getMorselSize();
    auto& propertyVector = output.getValueVectorMutable(3);
    auto propertyDataVec = ListVector::getDataVector(&propertyVector);
    for (auto i = 0u; i < numTuplesToOutput; i++) {
        auto indexInfo = indexesInfo[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, indexInfo.tableName);
        output.getValueVectorMutable(1).setValue(i, indexInfo.indexName);
        output.getValueVectorMutable(2).setValue(i, indexInfo.indexType);
        auto listEntry = ListVector::addList(&propertyVector, indexInfo.properties.size());
        for (auto j = 0u; j < indexInfo.properties.size(); j++) {
            propertyDataVec->setValue(listEntry.offset + j, indexInfo.properties[j]);
        }
        propertyVector.setValue(i, listEntry);
        output.getValueVectorMutable(4).setValue(i, indexInfo.dependencyLoaded);
        output.getValueVectorMutable(5).setValue(i, indexInfo.indexDefinition);
    }
    return numTuplesToOutput;
}

static binder::expression_vector bindColumns(const TableFuncBindInput& input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("table_name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("index_name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("index_type");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("property_names");
    columnTypes.emplace_back(LogicalType::LIST(LogicalType::STRING()));
    columnNames.emplace_back("extension_loaded");
    columnTypes.emplace_back(LogicalType::BOOL());
    columnNames.emplace_back("index_definition");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames = TableFunction::extractYieldVariables(columnNames, input.yieldVariables);
    return input.binder->createVariables(columnNames, columnTypes);
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<IndexInfo> indexesInfo;
    auto catalog = Catalog::Get(*context);
    auto transaction = transaction::Transaction::Get(*context);
    auto indexEntries = catalog->getIndexEntries(transaction);
    for (auto indexEntry : indexEntries) {
        auto tableEntry = catalog->getTableCatalogEntry(transaction, indexEntry->getTableID());
        auto tableName = tableEntry->getName();
        auto indexName = indexEntry->getIndexName();
        auto indexType = indexEntry->getIndexType();
        auto properties = indexEntry->getPropertyIDs();
        std::vector<std::string> propertyNames;
        for (auto& property : properties) {
            propertyNames.push_back(tableEntry->getProperty(property).getName());
        }
        auto dependencyLoaded = indexEntry->isLoaded();
        std::string indexDefinition;
        if (dependencyLoaded) {
            auto& auxInfo = indexEntry->getAuxInfo();
            common::FileScanInfo exportFileInfo{};
            IndexToCypherInfo info{context, exportFileInfo};
            indexDefinition = auxInfo.toCypher(*indexEntry, info);
        }
        indexesInfo.emplace_back(std::move(tableName), std::move(indexName), std::move(indexType),
            std::move(propertyNames), dependencyLoaded, std::move(indexDefinition));
    }
    return std::make_unique<ShowIndexesBindData>(indexesInfo, bindColumns(*input),
        indexesInfo.size());
}

function_set ShowIndexesFunction::getFunctionSet() {
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
