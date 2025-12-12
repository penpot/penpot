#include "processor/operator/simple/export_db.h"

#include <sstream>

#include "catalog/catalog.h"
#include "catalog/catalog_entry/index_catalog_entry.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "common/copier_config/csv_reader_config.h"
#include "common/file_system/virtual_file_system.h"
#include "common/string_utils.h"
#include "extension/extension_manager.h"
#include "function/scalar_macro_function.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::transaction;
using namespace lbug::catalog;
using namespace lbug::main;

namespace lbug {
namespace processor {

using std::stringstream;

std::string ExportDBPrintInfo::toString() const {
    std::string result = "Export To: ";
    result += filePath;
    if (!options.empty()) {
        result += ",Options: ";
        auto it = options.begin();
        for (auto i = 0u; it != options.end(); ++it, ++i) {
            result += it->first + "=" + it->second.toString();
            if (i < options.size() - 1) {
                result += ", ";
            }
        }
    }
    return result;
}

static void writeStringStreamToFile(ClientContext* context, const std::string& ssString,
    const std::string& path) {
    const auto fileInfo = VirtualFileSystem::GetUnsafe(*context)->openFile(path,
        FileOpenFlags(FileFlags::WRITE | FileFlags::CREATE_IF_NOT_EXISTS), context);
    fileInfo->writeFile(reinterpret_cast<const uint8_t*>(ssString.c_str()), ssString.size(),
        0 /* offset */);
}

static std::string getTablePropertyDefinitions(const TableCatalogEntry* entry) {
    std::string columns;
    auto properties = entry->getProperties();
    auto propertyIdx = 0u;
    for (auto& property : properties) {
        propertyIdx++;
        if (property.getType() == LogicalType::INTERNAL_ID()) {
            continue;
        }
        columns += "`" + property.getName() + "`";
        columns += propertyIdx == properties.size() ? "" : ",";
    }
    return columns;
}

static void writeCopyNodeStatement(stringstream& ss, const TableCatalogEntry* entry,
    const FileScanInfo* info,
    const std::unordered_map<std::string, const std::atomic<bool>*>& canUseParallelReader) {
    const auto csvConfig = CSVReaderConfig::construct(info->options);
    // TODO(Ziyi): We should pass fileName from binder phase to here.
    auto fileName = entry->getName() + "." + StringUtils::getLower(info->fileTypeInfo.fileTypeStr);
    std::string columns = getTablePropertyDefinitions(entry);
    bool useParallelReader = true;
    if (canUseParallelReader.contains(fileName)) {
        useParallelReader = canUseParallelReader.at(fileName)->load();
    }
    auto copyOptionsCypher = CSVOption::toCypher(csvConfig.option.toOptionsMap(useParallelReader));
    if (columns.empty()) {
        ss << stringFormat("COPY `{}` FROM \"{}\" {};\n", entry->getName(), fileName,
            copyOptionsCypher);
    } else {
        ss << stringFormat("COPY `{}` ({}) FROM \"{}\" {};\n", entry->getName(), columns, fileName,
            copyOptionsCypher);
    }
}

static void writeCopyRelStatement(stringstream& ss, const ClientContext* context,
    const TableCatalogEntry* entry, const FileScanInfo* info,
    const std::unordered_map<std::string, const std::atomic<bool>*>& canUseParallelReader) {
    const auto csvConfig = CSVReaderConfig::construct(info->options);
    std::string columns = getTablePropertyDefinitions(entry);
    auto transaction = Transaction::Get(*context);
    const auto catalog = Catalog::Get(*context);
    for (auto& entryInfo : entry->constCast<RelGroupCatalogEntry>().getRelEntryInfos()) {
        auto fromTableName =
            catalog->getTableCatalogEntry(transaction, entryInfo.nodePair.srcTableID)->getName();
        auto toTableName =
            catalog->getTableCatalogEntry(transaction, entryInfo.nodePair.dstTableID)->getName();
        // TODO(Ziyi): We should pass fileName from binder phase to here.
        auto fileName = stringFormat("{}_{}_{}.{}", entry->getName(), fromTableName, toTableName,
            StringUtils::getLower(info->fileTypeInfo.fileTypeStr));
        bool useParallelReader = true;
        if (canUseParallelReader.contains(fileName)) {
            useParallelReader = canUseParallelReader.at(fileName)->load();
        }
        auto copyOptionsMap = csvConfig.option.toOptionsMap(useParallelReader);
        copyOptionsMap["from"] = stringFormat("'{}'", fromTableName);
        copyOptionsMap["to"] = stringFormat("'{}'", toTableName);
        auto copyOptions = CSVOption::toCypher(copyOptionsMap);
        if (columns.empty()) {
            ss << stringFormat("COPY `{}` FROM \"{}\" {};\n", entry->getName(), fileName,
                copyOptions);
        } else {
            ss << stringFormat("COPY `{}` ({}) FROM \"{}\" {};\n", entry->getName(), columns,
                fileName, copyOptions);
        }
    }
}

static void exportLoadedExtensions(stringstream& ss, const ClientContext* clientContext) {
    auto extensionCypher = extension::ExtensionManager::Get(*clientContext)->toCypher();
    if (!extensionCypher.empty()) {
        ss << extensionCypher << std::endl;
    }
}

std::string getSchemaCypher(ClientContext* clientContext) {
    stringstream ss;
    exportLoadedExtensions(ss, clientContext);
    const auto catalog = Catalog::Get(*clientContext);
    auto transaction = Transaction::Get(*clientContext);
    ToCypherInfo toCypherInfo;
    for (const auto& nodeTableEntry :
        catalog->getNodeTableEntries(transaction, false /* useInternal */)) {
        ss << nodeTableEntry->toCypher(toCypherInfo) << std::endl;
    }
    RelGroupToCypherInfo relTableToCypherInfo{clientContext};
    for (const auto& entry : catalog->getRelGroupEntries(transaction, false /* useInternal */)) {
        ss << entry->toCypher(relTableToCypherInfo) << std::endl;
    }
    RelGroupToCypherInfo relGroupToCypherInfo{clientContext};
    for (const auto sequenceEntry : catalog->getSequenceEntries(transaction)) {
        ss << sequenceEntry->toCypher(relGroupToCypherInfo) << std::endl;
    }
    for (auto macroName : catalog->getMacroNames(transaction)) {
        ss << catalog->getScalarMacroFunction(transaction, macroName)->toCypher(macroName)
           << std::endl;
    }
    return ss.str();
}

std::string getCopyCypher(const ClientContext* context, const FileScanInfo* boundFileInfo,
    const std::unordered_map<std::string, const std::atomic<bool>*>& canUseParallelReader) {
    stringstream ss;
    auto transaction = Transaction::Get(*context);
    const auto catalog = Catalog::Get(*context);
    for (const auto& nodeTableEntry :
        catalog->getNodeTableEntries(transaction, false /* useInternal */)) {
        writeCopyNodeStatement(ss, nodeTableEntry, boundFileInfo, canUseParallelReader);
    }
    for (const auto& entry : catalog->getRelGroupEntries(transaction, false /* useInternal */)) {
        writeCopyRelStatement(ss, context, entry, boundFileInfo, canUseParallelReader);
    }
    return ss.str();
}

std::string getIndexCypher(ClientContext* clientContext, const FileScanInfo& exportFileInfo) {
    stringstream ss;
    IndexToCypherInfo info{clientContext, exportFileInfo};
    auto transaction = Transaction::Get(*clientContext);
    auto catalog = Catalog::Get(*clientContext);
    for (auto entry : catalog->getIndexEntries(transaction)) {
        auto indexCypher = entry->toCypher(info);
        if (!indexCypher.empty()) {
            ss << indexCypher << std::endl;
        }
    }
    return ss.str();
}

void ExportDB::executeInternal(ExecutionContext* context) {
    const auto clientContext = context->clientContext;
    // write the schema.cypher file
    writeStringStreamToFile(clientContext, getSchemaCypher(clientContext),
        boundFileInfo.filePaths[0] + "/" + PortDBConstants::SCHEMA_FILE_NAME);
    if (schemaOnly) {
        return;
    }
    // write the copy.cypher file
    // for every table, we write COPY FROM statement
    writeStringStreamToFile(clientContext,
        getCopyCypher(clientContext, &boundFileInfo, sharedState->canUseParallelReader),
        boundFileInfo.filePaths[0] + "/" + PortDBConstants::COPY_FILE_NAME);
    // write the index.cypher file
    writeStringStreamToFile(clientContext, getIndexCypher(clientContext, boundFileInfo),
        boundFileInfo.filePaths[0] + "/" + PortDBConstants::INDEX_FILE_NAME);
    appendMessage("Exported database successfully.", storage::MemoryManager::Get(*clientContext));
}

} // namespace processor
} // namespace lbug
