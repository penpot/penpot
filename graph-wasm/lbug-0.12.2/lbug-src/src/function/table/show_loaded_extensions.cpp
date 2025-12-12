#include "binder/binder.h"
#include "extension/extension.h"
#include "extension/extension_manager.h"
#include "function/table/bind_data.h"
#include "function/table/simple_table_function.h"
#include "main/client_context.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace function {

struct LoadedExtensionInfo {
    std::string name;
    extension::ExtensionSource extensionSource;
    std::string extensionPath;

    LoadedExtensionInfo(std::string name, extension::ExtensionSource extensionSource,
        std::string extensionPath)
        : name{std::move(name)}, extensionSource{extensionSource},
          extensionPath{std::move(extensionPath)} {}
};

struct ShowLoadedExtensionsBindData final : TableFuncBindData {
    std::vector<LoadedExtensionInfo> loadedExtensionInfo;

    ShowLoadedExtensionsBindData(std::vector<LoadedExtensionInfo> loadedExtensionInfo,
        binder::expression_vector columns, offset_t maxOffset)
        : TableFuncBindData{std::move(columns), maxOffset},
          loadedExtensionInfo{std::move(loadedExtensionInfo)} {}

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ShowLoadedExtensionsBindData>(*this);
    }
};

static offset_t internalTableFunc(const TableFuncMorsel& morsel, const TableFuncInput& input,
    DataChunk& output) {
    auto& loadedExtensions =
        input.bindData->constPtrCast<ShowLoadedExtensionsBindData>()->loadedExtensionInfo;
    auto numTuplesToOutput = morsel.getMorselSize();
    for (auto i = 0u; i < numTuplesToOutput; i++) {
        auto loadedExtension = loadedExtensions[morsel.startOffset + i];
        output.getValueVectorMutable(0).setValue(i, loadedExtension.name);
        output.getValueVectorMutable(1).setValue(i,
            extension::ExtensionSourceUtils::toString(loadedExtension.extensionSource));
        output.getValueVectorMutable(2).setValue(i, loadedExtension.extensionPath);
    }
    return numTuplesToOutput;
}

static binder::expression_vector bindColumns(const TableFuncBindInput& input) {
    std::vector<std::string> columnNames;
    std::vector<LogicalType> columnTypes;
    columnNames.emplace_back("extension name");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("extension source");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames.emplace_back("extension path");
    columnTypes.emplace_back(LogicalType::STRING());
    columnNames = TableFunction::extractYieldVariables(columnNames, input.yieldVariables);
    return input.binder->createVariables(columnNames, columnTypes);
}

static std::unique_ptr<TableFuncBindData> bindFunc(const main::ClientContext* context,
    const TableFuncBindInput* input) {
    auto loadedExtensions = extension::ExtensionManager::Get(*context)->getLoadedExtensions();
    std::vector<LoadedExtensionInfo> loadedExtensionInfo;
    for (auto& loadedExtension : loadedExtensions) {
        loadedExtensionInfo.emplace_back(loadedExtension.getExtensionName(),
            loadedExtension.getSource(), loadedExtension.getFullPath());
    }
    return std::make_unique<ShowLoadedExtensionsBindData>(loadedExtensionInfo, bindColumns(*input),
        loadedExtensionInfo.size());
}

function_set ShowLoadedExtensionsFunction::getFunctionSet() {
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
