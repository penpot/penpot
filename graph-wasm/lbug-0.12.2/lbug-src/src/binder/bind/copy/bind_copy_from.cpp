#include "binder/binder.h"
#include "binder/copy/bound_copy_from.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/index_catalog_entry.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/exception/binder.h"
#include "common/string_format.h"
#include "common/string_utils.h"
#include "parser/copy.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::function;

namespace lbug {
namespace binder {

static void throwTableNotExist(const std::string& tableName) {
    throw BinderException(stringFormat("Table {} does not exist.", tableName));
}

std::unique_ptr<BoundStatement> Binder::bindLegacyCopyRelGroupFrom(const Statement& statement) {
    auto& copyFrom = statement.constCast<CopyFrom>();
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto tableName = copyFrom.getTableName();
    auto tableNameParts = common::StringUtils::split(tableName, "_");
    if (tableNameParts.size() != 3 || !catalog->containsTable(transaction, tableNameParts[0])) {
        throwTableNotExist(tableName);
    }
    auto entry = catalog->getTableCatalogEntry(transaction, tableNameParts[0]);
    if (entry->getType() != CatalogEntryType::REL_GROUP_ENTRY) {
        throwTableNotExist(tableName);
    }
    auto relGroupEntry = entry->ptrCast<RelGroupCatalogEntry>();
    try {
        return bindCopyRelFrom(copyFrom, *relGroupEntry, tableNameParts[1], tableNameParts[2]);
    } catch (Exception& e) {
        throwTableNotExist(tableName);
        return nullptr;
    }
}

std::unique_ptr<BoundStatement> Binder::bindCopyFromClause(const Statement& statement) {
    auto& copyStatement = statement.constCast<CopyFrom>();
    auto tableName = copyStatement.getTableName();
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    if (!catalog->containsTable(transaction, tableName)) {
        return bindLegacyCopyRelGroupFrom(statement);
    }
    auto tableEntry = catalog->getTableCatalogEntry(transaction, tableName);
    switch (tableEntry->getType()) {
    case CatalogEntryType::NODE_TABLE_ENTRY: {
        return bindCopyNodeFrom(statement, *tableEntry->ptrCast<NodeTableCatalogEntry>());
    }
    case CatalogEntryType::REL_GROUP_ENTRY: {
        auto entry = tableEntry->ptrCast<RelGroupCatalogEntry>();
        auto properties = entry->getProperties();
        KU_ASSERT(entry->getNumRelTables() > 0);
        if (entry->getNumRelTables() == 1) {
            auto fromToNodePair = entry->getSingleRelEntryInfo().nodePair;
            auto fromTable = catalog->getTableCatalogEntry(transaction, fromToNodePair.srcTableID);
            auto toTable = catalog->getTableCatalogEntry(transaction, fromToNodePair.dstTableID);
            return bindCopyRelFrom(statement, *entry, fromTable->getName(), toTable->getName());
        } else {
            auto options = bindParsingOptions(copyStatement.getParsingOptions());
            if (!options.contains(CopyConstants::FROM_OPTION_NAME) ||
                !options.contains(CopyConstants::TO_OPTION_NAME)) {
                throw BinderException(stringFormat(
                    "The table {} has multiple FROM and TO pairs defined in the schema. A "
                    "specific pair of FROM and TO options is expected when copying data "
                    "into "
                    "the {} table.",
                    tableName, tableName));
            }
            auto from = options.at(CopyConstants::FROM_OPTION_NAME).getValue<std::string>();
            auto to = options.at(CopyConstants::TO_OPTION_NAME).getValue<std::string>();
            return bindCopyRelFrom(statement, *entry, from, to);
        }
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

static void bindExpectedNodeColumns(const NodeTableCatalogEntry& entry,
    const CopyFromColumnInfo& info, std::vector<std::string>& columnNames,
    std::vector<LogicalType>& columnTypes);
static void bindExpectedRelColumns(const RelGroupCatalogEntry& entry,
    const NodeTableCatalogEntry& fromEntry, const NodeTableCatalogEntry& toEntry,
    const CopyFromColumnInfo& info, std::vector<std::string>& columnNames,
    std::vector<LogicalType>& columnTypes);

static std::pair<ColumnEvaluateType, std::shared_ptr<Expression>> matchColumnExpression(
    const expression_vector& columns, const PropertyDefinition& property,
    ExpressionBinder& expressionBinder) {
    for (auto& column : columns) {
        if (property.getName() == column->toString()) {
            if (column->dataType == property.getType()) {
                return {ColumnEvaluateType::REFERENCE, column};
            } else {
                return {ColumnEvaluateType::CAST,
                    expressionBinder.forceCast(column, property.getType())};
            }
        }
    }
    return {ColumnEvaluateType::DEFAULT, expressionBinder.bindExpression(*property.defaultExpr)};
}

BoundCopyFromInfo Binder::bindCopyNodeFromInfo(std::string tableName,
    const std::vector<PropertyDefinition>& properties, const BaseScanSource* source,
    const options_t& parsingOptions, const std::vector<std::string>& expectedColumnNames,
    const std::vector<LogicalType>& expectedColumnTypes, bool byColumn) {
    auto boundSource =
        bindScanSource(source, parsingOptions, expectedColumnNames, expectedColumnTypes);
    expression_vector warningDataExprs = boundSource->getWarningColumns();
    if (boundSource->type == ScanSourceType::FILE) {
        auto bindData = boundSource->constCast<BoundTableScanSource>()
                            .info.bindData->constPtrCast<ScanFileBindData>();
        if (byColumn && bindData->fileScanInfo.fileTypeInfo.fileType != FileType::NPY) {
            throw BinderException(stringFormat("Copy by column with {} file type is not supported.",
                bindData->fileScanInfo.fileTypeInfo.fileTypeStr));
        }
    }
    expression_vector columns;
    std::vector<ColumnEvaluateType> evaluateTypes;
    for (auto& property : properties) {
        auto [evaluateType, column] =
            matchColumnExpression(boundSource->getColumns(), property, expressionBinder);
        columns.push_back(column);
        evaluateTypes.push_back(evaluateType);
    }
    columns.insert(columns.end(), warningDataExprs.begin(), warningDataExprs.end());
    auto offset =
        createInvisibleVariable(std::string(InternalKeyword::ROW_OFFSET), LogicalType::INT64());
    return BoundCopyFromInfo(tableName, TableType::NODE, std::move(boundSource), std::move(offset),
        std::move(columns), std::move(evaluateTypes), nullptr /* extraInfo */);
}

std::unique_ptr<BoundStatement> Binder::bindCopyNodeFrom(const Statement& statement,
    NodeTableCatalogEntry& nodeTableEntry) {
    auto& copyStatement = statement.constCast<CopyFrom>();
    // Check extension secondary index loaded
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    for (auto indexEntry : catalog->getIndexEntries(transaction, nodeTableEntry.getTableID())) {
        if (!indexEntry->isLoaded()) {
            throw BinderException(stringFormat(
                "Trying to insert into an index on table {} but its extension is not loaded.",
                nodeTableEntry.getName()));
        }
    }
    // Bind expected columns based on catalog information.
    std::vector<std::string> expectedColumnNames;
    std::vector<LogicalType> expectedColumnTypes;
    bindExpectedNodeColumns(nodeTableEntry, copyStatement.getCopyColumnInfo(), expectedColumnNames,
        expectedColumnTypes);
    auto boundCopyFromInfo =
        bindCopyNodeFromInfo(nodeTableEntry.getName(), nodeTableEntry.getProperties(),
            copyStatement.getSource(), copyStatement.getParsingOptions(), expectedColumnNames,
            expectedColumnTypes, copyStatement.byColumn());
    return std::make_unique<BoundCopyFrom>(std::move(boundCopyFromInfo));
}

static options_t getScanSourceOptions(const CopyFrom& copyFrom) {
    options_t options;
    static case_insensitve_set_t copyFromPairsOptions = {CopyConstants::FROM_OPTION_NAME,
        CopyConstants::TO_OPTION_NAME};
    for (auto& option : copyFrom.getParsingOptions()) {
        if (copyFromPairsOptions.contains(option.first)) {
            continue;
        }
        options.emplace(option.first, option.second->copy());
    }
    return options;
}

BoundCopyFromInfo Binder::bindCopyRelFromInfo(std::string tableName,
    const std::vector<PropertyDefinition>& properties, const BaseScanSource* source,
    const options_t& parsingOptions, const std::vector<std::string>& expectedColumnNames,
    const std::vector<LogicalType>& expectedColumnTypes, const NodeTableCatalogEntry* fromTable,
    const NodeTableCatalogEntry* toTable) {
    auto boundSource =
        bindScanSource(source, parsingOptions, expectedColumnNames, expectedColumnTypes);
    expression_vector warningDataExprs = boundSource->getWarningColumns();
    auto columns = boundSource->getColumns();
    auto offset =
        createInvisibleVariable(std::string(InternalKeyword::ROW_OFFSET), LogicalType::INT64());
    auto srcOffset = createVariable(std::string(InternalKeyword::SRC_OFFSET), LogicalType::INT64());
    auto dstOffset = createVariable(std::string(InternalKeyword::DST_OFFSET), LogicalType::INT64());
    expression_vector columnExprs{srcOffset, dstOffset, offset};
    std::vector<ColumnEvaluateType> evaluateTypes{ColumnEvaluateType::REFERENCE,
        ColumnEvaluateType::REFERENCE, ColumnEvaluateType::REFERENCE};
    for (auto i = 1u; i < properties.size(); ++i) { // skip internal ID
        auto& property = properties[i];
        auto [evaluateType, column] =
            matchColumnExpression(boundSource->getColumns(), property, expressionBinder);
        columnExprs.push_back(column);
        evaluateTypes.push_back(evaluateType);
    }
    columnExprs.insert(columnExprs.end(), warningDataExprs.begin(), warningDataExprs.end());
    std::shared_ptr<Expression> srcKey = nullptr, dstKey = nullptr;
    if (expectedColumnTypes[0] != columns[0]->getDataType()) {
        srcKey = expressionBinder.forceCast(columns[0], expectedColumnTypes[0]);
    } else {
        srcKey = columns[0];
    }
    if (expectedColumnTypes[1] != columns[1]->getDataType()) {
        dstKey = expressionBinder.forceCast(columns[1], expectedColumnTypes[1]);
    } else {
        dstKey = columns[1];
    }
    auto srcLookUpInfo =
        IndexLookupInfo(fromTable->getTableID(), srcOffset, srcKey, warningDataExprs);
    auto dstLookUpInfo =
        IndexLookupInfo(toTable->getTableID(), dstOffset, dstKey, warningDataExprs);
    auto lookupInfos = std::vector<IndexLookupInfo>{srcLookUpInfo, dstLookUpInfo};
    auto internalIDColumnIndices = std::vector<idx_t>{0, 1, 2};
    auto extraCopyRelInfo = std::make_unique<ExtraBoundCopyRelInfo>(fromTable->getName(),
        toTable->getName(), internalIDColumnIndices, lookupInfos);
    return BoundCopyFromInfo(tableName, TableType::REL, boundSource->copy(), offset,
        std::move(columnExprs), std::move(evaluateTypes), std::move(extraCopyRelInfo));
}

std::unique_ptr<BoundStatement> Binder::bindCopyRelFrom(const Statement& statement,
    RelGroupCatalogEntry& relGroupEntry, const std::string& fromTableName,
    const std::string& toTableName) {
    auto& copyStatement = statement.constCast<CopyFrom>();
    if (copyStatement.byColumn()) {
        throw BinderException(
            stringFormat("Copy by column is not supported for relationship table."));
    }
    // Bind from to tables
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto fromTable =
        catalog->getTableCatalogEntry(transaction, fromTableName)->ptrCast<NodeTableCatalogEntry>();
    auto toTable =
        catalog->getTableCatalogEntry(transaction, toTableName)->ptrCast<NodeTableCatalogEntry>();
    auto relInfo = relGroupEntry.getRelEntryInfo(fromTable->getTableID(), toTable->getTableID());
    if (relInfo == nullptr) {
        throw BinderException(stringFormat("Rel table {} does not contain {}-{} from-to pair.",
            relGroupEntry.getName(), fromTable->getName(), toTable->getName()));
    }
    // Bind expected columns based on catalog information.
    std::vector<std::string> expectedColumnNames;
    std::vector<LogicalType> expectedColumnTypes;
    bindExpectedRelColumns(relGroupEntry, *fromTable, *toTable, copyStatement.getCopyColumnInfo(),
        expectedColumnNames, expectedColumnTypes);
    // Bind info
    auto boundCopyFromInfo =
        bindCopyRelFromInfo(relGroupEntry.getName(), relGroupEntry.getProperties(),
            copyStatement.getSource(), getScanSourceOptions(copyStatement), expectedColumnNames,
            expectedColumnTypes, fromTable, toTable);
    return std::make_unique<BoundCopyFrom>(std::move(boundCopyFromInfo));
}

static bool skipPropertyInFile(const PropertyDefinition& property) {
    if (property.getName() == InternalKeyword::ID) {
        return true;
    }
    return false;
}

static bool skipPropertyInSchema(const PropertyDefinition& property) {
    if (property.getType().getLogicalTypeID() == LogicalTypeID::SERIAL) {
        return true;
    }
    if (property.getName() == InternalKeyword::ID) {
        return true;
    }
    return false;
}

static void bindExpectedColumns(const TableCatalogEntry& entry, const CopyFromColumnInfo& info,
    std::vector<std::string>& columnNames, std::vector<LogicalType>& columnTypes) {
    if (info.inputColumnOrder) {
        std::unordered_set<std::string> inputColumnNamesSet;
        for (auto& columName : info.columnNames) {
            if (inputColumnNamesSet.contains(columName)) {
                throw BinderException(
                    stringFormat("Detect duplicate column name {} during COPY.", columName));
            }
            inputColumnNamesSet.insert(columName);
        }
        // Search column data type for each input column.
        for (auto& columnName : info.columnNames) {
            if (!entry.containsProperty(columnName)) {
                throw BinderException(stringFormat("Table {} does not contain column {}.",
                    entry.getName(), columnName));
            }
            auto& property = entry.getProperty(columnName);
            if (skipPropertyInFile(property)) {
                continue;
            }
            columnNames.push_back(columnName);
            columnTypes.push_back(property.getType().copy());
        }
    } else {
        // No column specified. Fall back to schema columns.
        for (auto& property : entry.getProperties()) {
            if (skipPropertyInSchema(property)) {
                continue;
            }
            columnNames.push_back(property.getName());
            columnTypes.push_back(property.getType().copy());
        }
    }
}

void bindExpectedNodeColumns(const NodeTableCatalogEntry& entry, const CopyFromColumnInfo& info,
    std::vector<std::string>& columnNames, std::vector<LogicalType>& columnTypes) {
    KU_ASSERT(columnNames.empty() && columnTypes.empty());
    bindExpectedColumns(entry, info, columnNames, columnTypes);
}

void bindExpectedRelColumns(const RelGroupCatalogEntry& entry,
    const NodeTableCatalogEntry& fromEntry, const NodeTableCatalogEntry& toEntry,
    const CopyFromColumnInfo& info, std::vector<std::string>& columnNames,
    std::vector<LogicalType>& columnTypes) {
    KU_ASSERT(columnNames.empty() && columnTypes.empty());
    columnNames.push_back("from");
    columnNames.push_back("to");
    auto srcPKColumnType = fromEntry.getPrimaryKeyDefinition().getType().copy();
    if (srcPKColumnType.getLogicalTypeID() == LogicalTypeID::SERIAL) {
        srcPKColumnType = LogicalType::INT64();
    }
    auto dstPKColumnType = toEntry.getPrimaryKeyDefinition().getType().copy();
    if (dstPKColumnType.getLogicalTypeID() == LogicalTypeID::SERIAL) {
        dstPKColumnType = LogicalType::INT64();
    }
    columnTypes.push_back(std::move(srcPKColumnType));
    columnTypes.push_back(std::move(dstPKColumnType));
    bindExpectedColumns(entry, info, columnNames, columnTypes);
}

} // namespace binder
} // namespace lbug
