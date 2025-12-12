#include "binder/binder.h"
#include "binder/bound_import_database.h"
#include "common/copier_config/csv_reader_config.h"
#include "common/exception/binder.h"
#include "common/file_system/virtual_file_system.h"
#include "main/client_context.h"
#include "parser/copy.h"
#include "parser/parser.h"
#include "parser/port_db.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

static std::string getQueryFromFile(VirtualFileSystem* vfs, const std::string& boundFilePath,
    const std::string& fileName, main::ClientContext* context) {
    auto filePath = vfs->joinPath(boundFilePath, fileName);
    if (!vfs->fileOrPathExists(filePath, context)) {
        if (fileName == PortDBConstants::COPY_FILE_NAME) {
            return "";
        }
        if (fileName == PortDBConstants::INDEX_FILE_NAME) {
            return "";
        }
        throw BinderException(stringFormat("File {} does not exist.", filePath));
    }
    auto fileInfo = vfs->openFile(filePath, FileOpenFlags(FileFlags::READ_ONLY
#ifdef _WIN32
                                                          | FileFlags::BINARY
#endif
                                                ));
    auto fsize = fileInfo->getFileSize();
    auto buffer = std::make_unique<char[]>(fsize);
    fileInfo->readFile(buffer.get(), fsize);
    return std::string(buffer.get(), fsize);
}

static std::string getColumnNamesToCopy(const CopyFrom& copyFrom) {
    std::string columns = "";
    std::string delimiter = "";
    for (auto& column : copyFrom.getCopyColumnInfo().columnNames) {
        columns += delimiter;
        columns += "`" + column + "`";
        if (delimiter == "") {
            delimiter = ",";
        }
    }
    if (columns.empty()) {
        return columns;
    }
    return stringFormat("({})", columns);
}

static std::string getCopyFilePath(const std::string& boundFilePath, const std::string& filePath) {
    if (filePath[0] == '/' || (std::isalpha(filePath[0]) && filePath[1] == ':')) {
        // Note:
        // Unix absolute path starts with '/'
        // Windows absolute path starts with "[DiskID]://"
        // This code path is for backward compatibility, we used to export the absolute path for
        // csv files to copy.cypher files.
        return filePath;
    }

    auto path = boundFilePath + "/" + filePath;
#if defined(_WIN32)
    // TODO(Ziyi): This is a temporary workaround because our parser requires input cypher queries
    // to escape all special characters in string literal. E.g. The user input query is: 'IMPORT
    // DATABASE 'C:\\db\\uw''. The parser removes any escaped characters and this function accepts
    // the path parameter as 'C:\db\uw'. Then the ImportDatabase operator gives the file path to
    // antlr4 parser directly without escaping any special characters in the path, which causes a
    // parser exception. However, the parser exception is not thrown properly which leads to the
    // undefined behaviour.
    size_t pos = 0;
    while ((pos = path.find('\\', pos)) != std::string::npos) {
        path.replace(pos, 1, "\\\\");
        pos += 2;
    }
#endif
    return path;
}

std::unique_ptr<BoundStatement> Binder::bindImportDatabaseClause(const Statement& statement) {
    auto& importDB = statement.constCast<ImportDB>();
    auto fs = VirtualFileSystem::GetUnsafe(*clientContext);
    auto boundFilePath = fs->expandPath(clientContext, importDB.getFilePath());
    if (!fs->fileOrPathExists(boundFilePath, clientContext)) {
        throw BinderException(stringFormat("Directory {} does not exist.", boundFilePath));
    }
    std::string finalQueryStatements;
    finalQueryStatements +=
        getQueryFromFile(fs, boundFilePath, PortDBConstants::SCHEMA_FILE_NAME, clientContext);
    // replace the path in copy from statements with the bound path
    auto copyQuery =
        getQueryFromFile(fs, boundFilePath, PortDBConstants::COPY_FILE_NAME, clientContext);
    if (!copyQuery.empty()) {
        auto parsedStatements = Parser::parseQuery(copyQuery);
        for (auto& parsedStatement : parsedStatements) {
            KU_ASSERT(parsedStatement->getStatementType() == StatementType::COPY_FROM);
            auto& copyFromStatement = parsedStatement->constCast<CopyFrom>();
            KU_ASSERT(copyFromStatement.getSource()->type == ScanSourceType::FILE);
            auto filePaths =
                copyFromStatement.getSource()->constPtrCast<FileScanSource>()->filePaths;
            KU_ASSERT(filePaths.size() == 1);
            auto fileTypeInfo = bindFileTypeInfo(filePaths);
            std::string query;
            auto copyFilePath = getCopyFilePath(boundFilePath, filePaths[0]);
            auto columnNames = getColumnNamesToCopy(copyFromStatement);
            auto parsingOptions = bindParsingOptions(copyFromStatement.getParsingOptions());
            std::unordered_map<std::string, std::string> copyFromOptions;
            if (parsingOptions.contains(CopyConstants::FROM_OPTION_NAME)) {
                KU_ASSERT(parsingOptions.contains(CopyConstants::TO_OPTION_NAME));
                copyFromOptions[CopyConstants::FROM_OPTION_NAME] = stringFormat("'{}'",
                    parsingOptions.at(CopyConstants::FROM_OPTION_NAME).getValue<std::string>());
                copyFromOptions[CopyConstants::TO_OPTION_NAME] = stringFormat("'{}'",
                    parsingOptions.at(CopyConstants::TO_OPTION_NAME).getValue<std::string>());
                parsingOptions.erase(CopyConstants::FROM_OPTION_NAME);
                parsingOptions.erase(CopyConstants::TO_OPTION_NAME);
            }
            if (fileTypeInfo.fileType == FileType::CSV) {
                auto csvConfig = CSVReaderConfig::construct(parsingOptions);
                csvConfig.option.autoDetection = false;
                auto optionsMap = csvConfig.option.toOptionsMap(csvConfig.parallel);
                if (!copyFromOptions.empty()) {
                    optionsMap.insert(copyFromOptions.begin(), copyFromOptions.end());
                }
                query =
                    stringFormat("COPY `{}` {} FROM \"{}\" {};", copyFromStatement.getTableName(),
                        columnNames, copyFilePath, CSVOption::toCypher(optionsMap));
            } else {
                query =
                    stringFormat("COPY `{}` {} FROM \"{}\" {};", copyFromStatement.getTableName(),
                        columnNames, copyFilePath, CSVOption::toCypher(copyFromOptions));
            }
            finalQueryStatements += query;
        }
    }
    return std::make_unique<BoundImportDatabase>(boundFilePath, finalQueryStatements,
        getQueryFromFile(fs, boundFilePath, PortDBConstants::INDEX_FILE_NAME, clientContext));
}

} // namespace binder
} // namespace lbug
