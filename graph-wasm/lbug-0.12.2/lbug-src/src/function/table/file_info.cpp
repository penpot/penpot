#include "binder/binder.h"
#include "function/table/bind_data.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "storage/storage_manager.h"

namespace lbug {
namespace function {

struct FileInfoBindData final : TableFuncBindData {
    uint64_t numPages;

    FileInfoBindData(uint64_t numPages, binder::expression_vector columns)
        : TableFuncBindData{std::move(columns), 1}, numPages(numPages) {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<FileInfoBindData>(numPages, columns);
    }
};

static common::offset_t internalTableFunc(const TableFuncMorsel& /*morsel*/,
    const TableFuncInput& input, common::DataChunk& output) {
    KU_ASSERT(output.getNumValueVectors() == 1);
    auto fileInfoBindData = input.bindData->constPtrCast<FileInfoBindData>();
    output.getValueVectorMutable(0).setValue<uint64_t>(0, fileInfoBindData->numPages);
    return 1;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    auto numPages = storage::StorageManager::Get(*context)->getDataFH()->getNumPages();
    std::vector<common::LogicalType> returnTypes;
    returnTypes.emplace_back(common::LogicalType::UINT64());
    auto returnColumnNames = std::vector<std::string>{"num_pages"};
    returnColumnNames =
        TableFunction::extractYieldVariables(returnColumnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(returnColumnNames, returnTypes);
    return std::make_unique<FileInfoBindData>(numPages, columns);
}

function_set FileInfoFunction::getFunctionSet() {
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
