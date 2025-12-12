#include "binder/binder.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/simple_table_function.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;

namespace lbug {
namespace function {

struct SequenceInfo {
    std::string name;
    std::string databaseName;
    int64_t startValue;
    int64_t increment;
    int64_t minValue;
    int64_t maxValue;
    bool cycle;

    SequenceInfo(std::string name, std::string databaseName, int64_t startValue, int64_t increment,
        int64_t minValue, int64_t maxValue, bool cycle)
        : name{std::move(name)}, databaseName{std::move(databaseName)}, startValue{startValue},
          increment{increment}, minValue{minValue}, maxValue{maxValue}, cycle{cycle} {}
};

struct ShowSequencesBindData final : TableFuncBindData {
    std::vector<SequenceInfo> sequences;

    ShowSequencesBindData(std::vector<SequenceInfo> sequences, binder::expression_vector columns,
        offset_t maxOffset)
        : TableFuncBindData{std::move(columns), maxOffset}, sequences{std::move(sequences)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowSequencesBindData>(sequences, columns, numRows);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    const auto sequences = input.bindData->constPtrCast<ShowSequencesBindData>()->sequences;
    const auto numSequencesToOutput = morsel.endOffset - morsel.startOffset;
    for (auto i = 0u; i < numSequencesToOutput; i++) {
        const auto sequenceInfo = sequences[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, sequenceInfo.name);
        output.getValueVectorMutable(1).setValue(i, sequenceInfo.databaseName);
        output.getValueVectorMutable(2).setValue(i, sequenceInfo.startValue);
        output.getValueVectorMutable(3).setValue(i, sequenceInfo.increment);
        output.getValueVectorMutable(4).setValue(i, sequenceInfo.minValue);
        output.getValueVectorMutable(5).setValue(i, sequenceInfo.maxValue);
        output.getValueVectorMutable(6).setValue(i, sequenceInfo.cycle);
    }
    return numSequencesToOutput;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("database name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("start value");
    columnTypes.emplace_back(LogicalType::INT64());
    columnNames.emplace_back("increment");
    columnTypes.emplace_back(LogicalType::INT64());
    columnNames.emplace_back("min value");
    columnTypes.emplace_back(LogicalType::INT64());
    columnNames.emplace_back("max value");
    columnTypes.emplace_back(LogicalType::INT64());
    columnNames.emplace_back("cycle");
    columnTypes.emplace_back(LogicalType::BOOL());
    std::vector<SequenceInfo> sequenceInfos;
    for (const auto& entry :
        Catalog::Get(*context)->getSequenceEntries(transaction::Transaction::Get(*context))) {
        const auto sequenceData = entry->getSequenceData();
        auto sequenceInfo = SequenceInfo{entry->getName(), LOCAL_DB_NAME, sequenceData.startValue,
            sequenceData.increment, sequenceData.minValue, sequenceData.maxValue,
            sequenceData.cycle};
        sequenceInfos.push_back(std::move(sequenceInfo));
    }

    // TODO: uncomment this when we can test it
    // for (auto attachedDatabase : databaseManager->getAttachedDatabases()) {
    //     auto databaseName = attachedDatabase->getDBName();
    //     auto databaseType = attachedDatabase->getDBType();
    //     for (auto& entry : attachedDatabase->getCatalog()->getSequenceEntries(context->getTx()))
    //     {
    //         auto sequenceData = entry->getSequenceData();
    //         auto sequenceInfo =
    //             SequenceInfo{entry->getName(), stringFormat("{}({})", databaseName,
    //             databaseType),
    //                 sequenceData.startValue, sequenceData.increment, sequenceData.minValue,
    //                 sequenceData.maxValue, sequenceData.cycle};
    //         sequenceInfos.push_back(std::move(sequenceInfo));
    //     }
    // }
    columnNames = TableFunction::extractYieldVariables(columnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(columnNames, columnTypes);
    return std::make_unique<ShowSequencesBindData>(std::move(sequenceInfos), columns,
        sequenceInfos.size());
}

function_set ShowSequencesFunction::getFunctionSet() {
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
