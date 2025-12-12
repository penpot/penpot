#include "binder/binder.h"
#include "binder/bound_scan_source.h"
#include "binder/expression/literal_expression.h"
#include "binder/expression/parameter_expression.h"
#include "common/exception/binder.h"
#include "common/exception/copy.h"
#include "common/exception/message.h"
#include "common/file_system/local_file_system.h"
#include "common/file_system/virtual_file_system.h"
#include "common/string_format.h"
#include "common/string_utils.h"
#include "extension/extension_manager.h"
#include "function/table/bind_input.h"
#include "main/client_context.h"
#include "main/database_manager.h"
#include "parser/expression/parsed_function_expression.h"
#include "parser/scan_source.h"

using namespace lbug::parser;
using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::function;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

FileTypeInfo bindSingleFileType(const main::ClientContext* context, const std::string& filePath) {
    std::filesystem::path fileName(filePath);
    auto extension = VirtualFileSystem::GetUnsafe(*context)->getFileExtension(fileName);
    return FileTypeInfo{FileTypeUtils::getFileTypeFromExtension(extension),
        extension.substr(std::min<uint64_t>(1, extension.length()))};
}

FileTypeInfo Binder::bindFileTypeInfo(const std::vector<std::string>& filePaths) const {
    auto expectedFileType = FileTypeInfo{FileType::UNKNOWN, "" /* fileTypeStr */};
    for (auto& filePath : filePaths) {
        auto fileType = bindSingleFileType(clientContext, filePath);
        expectedFileType =
            (expectedFileType.fileType == FileType::UNKNOWN) ? fileType : expectedFileType;
        if (fileType.fileType != expectedFileType.fileType) {
            throw CopyException("Loading files with different types is not currently supported.");
        }
    }
    return expectedFileType;
}

std::vector<std::string> Binder::bindFilePaths(const std::vector<std::string>& filePaths) const {
    std::vector<std::string> boundFilePaths;
    for (auto& filePath : filePaths) {
        // This is a temporary workaround because we use duckdb to read from iceberg/delta/azure.
        // When we read delta/iceberg/azure tables from s3/httpfs, we don't have the httpfs
        // extension loaded meaning that we cannot handle remote paths. So we pass the file path to
        // duckdb for validation when we bindFileScanSource.
        const auto& loadedExtensions =
            extension::ExtensionManager::Get(*clientContext)->getLoadedExtensions();
        const bool httpfsExtensionLoaded =
            std::any_of(loadedExtensions.begin(), loadedExtensions.end(),
                [](const auto& extension) { return extension.getExtensionName() == "HTTPFS"; });
        if (!httpfsExtensionLoaded && !LocalFileSystem::isLocalPath(filePath)) {
            boundFilePaths.push_back(filePath);
            continue;
        }
        auto globbedFilePaths =
            VirtualFileSystem::GetUnsafe(*clientContext)->glob(clientContext, filePath);
        if (globbedFilePaths.empty()) {
            throw BinderException{
                stringFormat("No file found that matches the pattern: {}.", filePath)};
        }
        for (auto& globbedPath : globbedFilePaths) {
            boundFilePaths.push_back(globbedPath);
        }
    }
    return boundFilePaths;
}

case_insensitive_map_t<Value> Binder::bindParsingOptions(const options_t& parsingOptions) {
    case_insensitive_map_t<Value> options;
    for (auto& option : parsingOptions) {
        auto name = option.first;
        StringUtils::toUpper(name);
        auto expr = expressionBinder.bindExpression(*option.second);
        KU_ASSERT(expr->expressionType == ExpressionType::LITERAL);
        auto literalExpr = ku_dynamic_cast<LiteralExpression*>(expr.get());
        options.insert({name, literalExpr->getValue()});
    }
    return options;
}

std::unique_ptr<BoundBaseScanSource> Binder::bindScanSource(const BaseScanSource* source,
    const options_t& options, const std::vector<std::string>& columnNames,
    const std::vector<LogicalType>& columnTypes) {
    switch (source->type) {
    case ScanSourceType::FILE: {
        return bindFileScanSource(*source, options, columnNames, columnTypes);
    }
    case ScanSourceType::QUERY: {
        return bindQueryScanSource(*source, options, columnNames, columnTypes);
    }
    case ScanSourceType::OBJECT: {
        return bindObjectScanSource(*source, options, columnNames, columnTypes);
    }
    case ScanSourceType::TABLE_FUNC: {
        return bindTableFuncScanSource(*source, options, columnNames, columnTypes);
    }
    case ScanSourceType::PARAM: {
        return bindParameterScanSource(*source, options, columnNames, columnTypes);
    }
    default:
        KU_UNREACHABLE;
    }
}

bool handleFileViaFunction(main::ClientContext* context, std::vector<std::string> filePaths) {
    bool handleFileViaFunction = false;
    if (VirtualFileSystem::GetUnsafe(*context)->fileOrPathExists(filePaths[0], context)) {
        handleFileViaFunction =
            VirtualFileSystem::GetUnsafe(*context)->handleFileViaFunction(filePaths[0]);
    }
    return handleFileViaFunction;
}

std::unique_ptr<BoundBaseScanSource> Binder::bindFileScanSource(const BaseScanSource& scanSource,
    const options_t& options, const std::vector<std::string>& columnNames,
    const std::vector<LogicalType>& columnTypes) {
    auto fileSource = scanSource.constPtrCast<FileScanSource>();
    auto filePaths = bindFilePaths(fileSource->filePaths);
    auto boundOptions = bindParsingOptions(options);
    FileTypeInfo fileTypeInfo;

    if (boundOptions.contains(FileScanInfo::FILE_FORMAT_OPTION_NAME)) {
        auto fileFormat = boundOptions.at(FileScanInfo::FILE_FORMAT_OPTION_NAME).toString();
        fileTypeInfo = FileTypeInfo{FileTypeUtils::fromString(fileFormat), fileFormat};
    } else {
        fileTypeInfo = bindFileTypeInfo(filePaths);
    }
    // If we defined a certain FileType, we have to ensure the path is a file, not something else
    // (e.g. an existed directory)
    if (fileTypeInfo.fileType != FileType::UNKNOWN) {
        for (const auto& filePath : filePaths) {
            if (!LocalFileSystem::fileExists(filePath) && LocalFileSystem::isLocalPath(filePath)) {
                throw BinderException{stringFormat("Provided path is not a file: {}.", filePath)};
            }
        }
    }
    boundOptions.erase(FileScanInfo::FILE_FORMAT_OPTION_NAME);
    // Bind file configuration
    auto fileScanInfo = std::make_unique<FileScanInfo>(std::move(fileTypeInfo), filePaths);
    fileScanInfo->options = std::move(boundOptions);
    TableFunction func;
    if (handleFileViaFunction(clientContext, filePaths)) {
        func = VirtualFileSystem::GetUnsafe(*clientContext)->getHandleFunction(filePaths[0]);
    } else {
        func = getScanFunction(fileScanInfo->fileTypeInfo, *fileScanInfo);
    }
    // Bind table function
    auto bindInput = TableFuncBindInput();
    bindInput.addLiteralParam(Value::createValue(filePaths[0]));
    auto extraInput = std::make_unique<ExtraScanTableFuncBindInput>();
    extraInput->fileScanInfo = fileScanInfo->copy();
    extraInput->expectedColumnNames = columnNames;
    extraInput->expectedColumnTypes = LogicalType::copy(columnTypes);
    extraInput->tableFunction = &func;
    bindInput.extraInput = std::move(extraInput);
    bindInput.binder = this;
    auto bindData = func.bindFunc(clientContext, &bindInput);
    auto info = BoundTableScanInfo(func, std::move(bindData));
    return std::make_unique<BoundTableScanSource>(ScanSourceType::FILE, std::move(info));
}

std::unique_ptr<BoundBaseScanSource> Binder::bindQueryScanSource(const BaseScanSource& scanSource,
    const options_t& options, const std::vector<std::string>& columnNames,
    const std::vector<LogicalType>&) {
    auto querySource = scanSource.constPtrCast<QueryScanSource>();
    auto boundStatement = bind(*querySource->statement);
    auto columns = boundStatement->getStatementResult()->getColumns();
    if (columns.size() != columnNames.size()) {
        throw BinderException(stringFormat("Query returns {} columns but {} columns were expected.",
            columns.size(), columnNames.size()));
    }
    for (auto i = 0u; i < columns.size(); ++i) {
        columns[i]->setAlias(columnNames[i]);
    }
    auto scanInfo = BoundQueryScanSourceInfo(bindParsingOptions(options));
    return std::make_unique<BoundQueryScanSource>(std::move(boundStatement), std::move(scanInfo));
}

static TableFunction getObjectScanFunc(const std::string& dbName, const std::string& tableName,
    main::ClientContext* clientContext) {
    // Bind external database table
    auto attachedDB = main::DatabaseManager::Get(*clientContext)->getAttachedDatabase(dbName);
    auto attachedCatalog = attachedDB->getCatalog();
    auto entry = attachedCatalog->getTableCatalogEntry(
        transaction::Transaction::Get(*clientContext), tableName);
    return entry->ptrCast<TableCatalogEntry>()->getScanFunction();
}

BoundTableScanInfo bindTableScanSourceInfo(Binder& binder, TableFunction func,
    const std::string& sourceName, std::unique_ptr<TableFuncBindData> bindData,
    const std::vector<std::string>& columnNames, const std::vector<LogicalType>& columnTypes) {
    expression_vector columns;
    if (columnTypes.empty()) {
    } else {
        if (bindData->getNumColumns() != columnTypes.size()) {
            throw BinderException(stringFormat("{} has {} columns but {} columns were expected.",
                sourceName, bindData->getNumColumns(), columnTypes.size()));
        }
        for (auto i = 0u; i < bindData->getNumColumns(); ++i) {
            auto column =
                binder.createInvisibleVariable(columnNames[i], bindData->columns[i]->getDataType());
            binder.replaceExpressionInScope(bindData->columns[i]->toString(), columnNames[i],
                column);
            columns.push_back(column);
        }
        bindData->columns = columns;
    }
    return BoundTableScanInfo(func, std::move(bindData));
}

std::unique_ptr<BoundBaseScanSource> Binder::bindParameterScanSource(
    const BaseScanSource& scanSource, const options_t& options,
    const std::vector<std::string>& columnNames, const std::vector<LogicalType>& columnTypes) {
    auto paramSource = scanSource.constPtrCast<ParameterScanSource>();
    auto paramExpr = expressionBinder.bindParameterExpression(*paramSource->paramExpression);
    auto scanSourceValue = paramExpr->constCast<ParameterExpression>().getValue();
    if (scanSourceValue.getDataType().getLogicalTypeID() != LogicalTypeID::POINTER) {
        throw BinderException(stringFormat(
            "Trying to scan from unsupported data type {}. The only parameter types that can be "
            "scanned from are pandas/polars dataframes and pyarrow tables.",
            scanSourceValue.getDataType().toString()));
    }
    TableFunction func;
    std::unique_ptr<TableFuncBindData> bindData;
    auto bindInput = TableFuncBindInput();
    bindInput.binder = this;
    // Bind external object as table
    auto replacementData =
        clientContext->tryReplaceByHandle(scanSourceValue.getValue<scan_replace_handle_t>());
    func = replacementData->func;
    auto replaceExtraInput = std::make_unique<ExtraScanTableFuncBindInput>();
    replaceExtraInput->fileScanInfo.options = bindParsingOptions(options);
    replacementData->bindInput.extraInput = std::move(replaceExtraInput);
    replacementData->bindInput.binder = this;
    bindData = func.bindFunc(clientContext, &replacementData->bindInput);
    auto info = bindTableScanSourceInfo(*this, func, paramExpr->toString(), std::move(bindData),
        columnNames, columnTypes);
    return std::make_unique<BoundTableScanSource>(ScanSourceType::OBJECT, std::move(info));
}

std::unique_ptr<BoundBaseScanSource> Binder::bindObjectScanSource(const BaseScanSource& scanSource,
    const options_t& options, const std::vector<std::string>& columnNames,
    const std::vector<LogicalType>& columnTypes) {
    auto objectSource = scanSource.constPtrCast<ObjectScanSource>();
    TableFunction func;
    std::unique_ptr<TableFuncBindData> bindData;
    std::string objectName;
    auto bindInput = TableFuncBindInput();
    bindInput.binder = this;
    if (objectSource->objectNames.size() == 1) {
        // Bind external object as table
        objectName = objectSource->objectNames[0];
        auto replacementData = clientContext->tryReplaceByName(objectName);
        if (replacementData != nullptr) { // Replace as python object
            func = replacementData->func;
            auto replaceExtraInput = std::make_unique<ExtraScanTableFuncBindInput>();
            replaceExtraInput->fileScanInfo.options = bindParsingOptions(options);
            replacementData->bindInput.extraInput = std::move(replaceExtraInput);
            replacementData->bindInput.binder = this;
            bindData = func.bindFunc(clientContext, &replacementData->bindInput);
        } else if (main::DatabaseManager::Get(*clientContext)->hasDefaultDatabase()) {
            auto dbName = main::DatabaseManager::Get(*clientContext)->getDefaultDatabase();
            func = getObjectScanFunc(dbName, objectSource->objectNames[0], clientContext);
            bindData = func.bindFunc(clientContext, &bindInput);
        } else {
            throw BinderException(ExceptionMessage::variableNotInScope(objectName));
        }
    } else if (objectSource->objectNames.size() == 2) {
        // Bind external database table
        objectName = objectSource->objectNames[0] + "." + objectSource->objectNames[1];
        func = getObjectScanFunc(objectSource->objectNames[0], objectSource->objectNames[1],
            clientContext);
        bindData = func.bindFunc(clientContext, &bindInput);
    } else {
        // LCOV_EXCL_START
        throw BinderException(stringFormat("Cannot find object {}.",
            StringUtils::join(objectSource->objectNames, ",")));
        // LCOV_EXCL_STOP
    }
    auto info = bindTableScanSourceInfo(*this, func, objectName, std::move(bindData), columnNames,
        columnTypes);
    return std::make_unique<BoundTableScanSource>(ScanSourceType::OBJECT, std::move(info));
}

std::unique_ptr<BoundBaseScanSource> Binder::bindTableFuncScanSource(
    const BaseScanSource& scanSource, const options_t& options,
    const std::vector<std::string>& columnNames, const std::vector<LogicalType>& columnTypes) {
    if (!options.empty()) {
        throw common::BinderException{"No option is supported when copying from table functions."};
    }
    auto tableFuncScanSource = scanSource.constPtrCast<TableFuncScanSource>();
    auto& parsedFuncExpression =
        tableFuncScanSource->functionExpression->constCast<parser::ParsedFunctionExpression>();
    auto boundTableFunc = bindTableFunc(parsedFuncExpression.getFunctionName(),
        *tableFuncScanSource->functionExpression, {} /* yieldVariables */);
    auto& tableFunc = boundTableFunc.func;
    auto info = bindTableScanSourceInfo(*this, tableFunc, tableFunc.name,
        std::move(boundTableFunc.bindData), columnNames, columnTypes);
    return std::make_unique<BoundTableScanSource>(ScanSourceType::OBJECT, std::move(info));
}

} // namespace binder
} // namespace lbug
