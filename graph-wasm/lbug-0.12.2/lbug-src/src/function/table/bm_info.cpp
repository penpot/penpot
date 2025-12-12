#include "binder/binder.h"
#include "function/table/bind_data.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace function {

struct BMInfoBindData final : TableFuncBindData {
    uint64_t memLimit;
    uint64_t memUsage;

    BMInfoBindData(uint64_t memLimit, uint64_t memUsage, binder::expression_vector columns)
        : TableFuncBindData{std::move(columns), 1}, memLimit{memLimit}, memUsage{memUsage} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<BMInfoBindData>(memLimit, memUsage, columns);
    }
};

static common::offset_t internalTableFunc(const TableFuncMorsel& /*morsel*/,
    const TableFuncInput& input, common::DataChunk& output) {
    KU_ASSERT(output.getNumValueVectors() == 2);
    auto bmInfoBindData = input.bindData->constPtrCast<BMInfoBindData>();
    output.getValueVectorMutable(0).setValue<uint64_t>(0, bmInfoBindData->memLimit);
    output.getValueVectorMutable(1).setValue<uint64_t>(0, bmInfoBindData->memUsage);
    return 1;
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    auto memLimit = storage::MemoryManager::Get(*context)->getBufferManager()->getMemoryLimit();
    auto memUsage = storage::MemoryManager::Get(*context)->getBufferManager()->getUsedMemory();
    std::vector<common::LogicalType> returnTypes;
    returnTypes.emplace_back(common::LogicalType::UINT64());
    returnTypes.emplace_back(common::LogicalType::UINT64());
    auto returnColumnNames = std::vector<std::string>{"mem_limit", "mem_usage"};
    returnColumnNames =
        TableFunction::extractYieldVariables(returnColumnNames, input->yieldVariables);
    auto columns = input->binder->createVariables(returnColumnNames, returnTypes);
    return std::make_unique<BMInfoBindData>(memLimit, memUsage, columns);
}

function_set BMInfoFunction::getFunctionSet() {
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
