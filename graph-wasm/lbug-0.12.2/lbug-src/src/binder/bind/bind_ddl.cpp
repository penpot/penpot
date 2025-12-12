#include "binder/binder.h"
#include "binder/ddl/bound_alter.h"
#include "binder/ddl/bound_create_sequence.h"
#include "binder/ddl/bound_create_table.h"
#include "binder/ddl/bound_create_type.h"
#include "binder/ddl/bound_drop.h"
#include "binder/expression_visitor.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "common/enums/extend_direction_util.h"
#include "common/exception/binder.h"
#include "common/exception/message.h"
#include "common/string_format.h"
#include "common/system_config.h"
#include "common/types/types.h"
#include "function/cast/functions/cast_from_string_functions.h"
#include "function/sequence/sequence_functions.h"
#include "main/client_context.h"
#include "parser/ddl/alter.h"
#include "parser/ddl/create_sequence.h"
#include "parser/ddl/create_table.h"
#include "parser/ddl/create_table_info.h"
#include "parser/ddl/create_type.h"
#include "parser/ddl/drop.h"
#include "parser/expression/parsed_function_expression.h"
#include "parser/expression/parsed_literal_expression.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::catalog;

namespace lbug {
namespace binder {

static void validatePropertyName(const std::vector<PropertyDefinition>& definitions) {
    case_insensitve_set_t nameSet;
    for (auto& definition : definitions) {
        if (nameSet.contains(definition.getName())) {
            throw BinderException(stringFormat(
                "Duplicated column name: {}, column name must be unique.", definition.getName()));
        }
        if (Binder::reservedInColumnName(definition.getName())) {
            throw BinderException(
                stringFormat("{} is a reserved property name.", definition.getName()));
        }
        nameSet.insert(definition.getName());
    }
}

std::vector<PropertyDefinition> Binder::bindPropertyDefinitions(
    const std::vector<ParsedPropertyDefinition>& parsedDefinitions, const std::string& tableName) {
    std::vector<PropertyDefinition> definitions;
    for (auto& def : parsedDefinitions) {
        auto type = LogicalType::convertFromString(def.getType(), clientContext);
        auto defaultExpr =
            resolvePropertyDefault(def.defaultExpr.get(), type, tableName, def.getName());
        auto boundExpr = expressionBinder.bindExpression(*defaultExpr);
        if (boundExpr->dataType != type) {
            expressionBinder.implicitCast(boundExpr, type);
        }
        auto columnDefinition = ColumnDefinition(def.getName(), std::move(type));
        definitions.emplace_back(std::move(columnDefinition), std::move(defaultExpr));
    }
    validatePropertyName(definitions);
    return definitions;
}

std::unique_ptr<ParsedExpression> Binder::resolvePropertyDefault(ParsedExpression* parsedDefault,
    const LogicalType& type, const std::string& tableName, const std::string& propertyName) {
    if (parsedDefault == nullptr) { // No default provided.
        if (type.getLogicalTypeID() == LogicalTypeID::SERIAL) {
            auto serialName = SequenceCatalogEntry::getSerialName(tableName, propertyName);
            auto literalExpr = std::make_unique<ParsedLiteralExpression>(Value(serialName), "");
            return std::make_unique<ParsedFunctionExpression>(function::NextValFunction::name,
                std::move(literalExpr), "" /* rawName */);
        } else {
            return std::make_unique<ParsedLiteralExpression>(Value::createNullValue(type), "NULL");
        }
    } else {
        if (type.getLogicalTypeID() == LogicalTypeID::SERIAL) {
            throw BinderException("No DEFAULT value should be set for SERIAL columns");
        }
        return parsedDefault->copy();
    }
}

static void validatePrimaryKey(const std::string& pkColName,
    const std::vector<PropertyDefinition>& definitions) {
    uint32_t primaryKeyIdx = UINT32_MAX;
    for (auto i = 0u; i < definitions.size(); i++) {
        if (definitions[i].getName() == pkColName) {
            primaryKeyIdx = i;
        }
    }
    if (primaryKeyIdx == UINT32_MAX) {
        throw BinderException(
            "Primary key " + pkColName + " does not match any of the predefined node properties.");
    }
    const auto& pkType = definitions[primaryKeyIdx].getType();
    if (!pkType.isInternalType()) {
        throw BinderException(ExceptionMessage::invalidPKType(pkType.toString()));
    }
    switch (pkType.getPhysicalType()) {
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT128:
    case PhysicalTypeID::UINT128:
    case PhysicalTypeID::STRING:
    case PhysicalTypeID::FLOAT:
    case PhysicalTypeID::DOUBLE:
        break;
    default:
        throw BinderException(ExceptionMessage::invalidPKType(pkType.toString()));
    }
}

BoundCreateTableInfo Binder::bindCreateTableInfo(const CreateTableInfo* info) {
    switch (info->type) {
    case TableType::NODE: {
        return bindCreateNodeTableInfo(info);
    }
    case TableType::REL: {
        return bindCreateRelTableGroupInfo(info);
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

BoundCreateTableInfo Binder::bindCreateNodeTableInfo(const CreateTableInfo* info) {
    auto propertyDefinitions = bindPropertyDefinitions(info->propertyDefinitions, info->tableName);
    auto& extraInfo = info->extraInfo->constCast<ExtraCreateNodeTableInfo>();
    validatePrimaryKey(extraInfo.pKName, propertyDefinitions);
    auto boundExtraInfo = std::make_unique<BoundExtraCreateNodeTableInfo>(extraInfo.pKName,
        std::move(propertyDefinitions));
    return BoundCreateTableInfo(CatalogEntryType::NODE_TABLE_ENTRY, info->tableName,
        info->onConflict, std::move(boundExtraInfo), clientContext->useInternalCatalogEntry());
}

void Binder::validateNodeTableType(const TableCatalogEntry* entry) {
    if (entry->getType() != CatalogEntryType::NODE_TABLE_ENTRY) {
        throw BinderException(stringFormat("{} is not of type NODE.", entry->getName()));
    }
}

void Binder::validateTableExistence(const main::ClientContext& context,
    const std::string& tableName) {
    auto transaction = transaction::Transaction::Get(context);
    if (!Catalog::Get(context)->containsTable(transaction, tableName)) {
        throw BinderException{stringFormat("Table {} does not exist.", tableName)};
    }
}

void Binder::validateColumnExistence(const TableCatalogEntry* entry,
    const std::string& columnName) {
    if (!entry->containsProperty(columnName)) {
        throw BinderException{
            stringFormat("Column {} does not exist in table {}.", columnName, entry->getName())};
    }
}

static ExtendDirection getStorageDirection(const case_insensitive_map_t<Value>& options) {
    if (options.contains(TableOptionConstants::REL_STORAGE_DIRECTION_OPTION)) {
        return ExtendDirectionUtil::fromString(
            options.at(TableOptionConstants::REL_STORAGE_DIRECTION_OPTION).toString());
    }
    return DEFAULT_EXTEND_DIRECTION;
}

std::vector<PropertyDefinition> Binder::bindRelPropertyDefinitions(const CreateTableInfo& info) {
    std::vector<PropertyDefinition> propertyDefinitions;
    propertyDefinitions.emplace_back(
        ColumnDefinition(InternalKeyword::ID, LogicalType::INTERNAL_ID()));
    for (auto& definition : bindPropertyDefinitions(info.propertyDefinitions, info.tableName)) {
        propertyDefinitions.push_back(definition.copy());
    }
    return propertyDefinitions;
}

BoundCreateTableInfo Binder::bindCreateRelTableGroupInfo(const CreateTableInfo* info) {
    auto propertyDefinitions = bindRelPropertyDefinitions(*info);
    auto& extraInfo = info->extraInfo->constCast<ExtraCreateRelTableGroupInfo>();
    auto srcMultiplicity = RelMultiplicityUtils::getFwd(extraInfo.relMultiplicity);
    auto dstMultiplicity = RelMultiplicityUtils::getBwd(extraInfo.relMultiplicity);
    auto boundOptions = bindParsingOptions(extraInfo.options);
    auto storageDirection = getStorageDirection(boundOptions);
    // Bind from to pairs
    node_table_id_pair_set_t nodePairsSet;
    std::vector<NodeTableIDPair> nodePairs;
    for (auto& [srcTableName, dstTableName] : extraInfo.srcDstTablePairs) {
        auto srcEntry = bindNodeTableEntry(srcTableName);
        validateNodeTableType(srcEntry);
        auto dstEntry = bindNodeTableEntry(dstTableName);
        validateNodeTableType(dstEntry);
        NodeTableIDPair pair{srcEntry->getTableID(), dstEntry->getTableID()};
        if (nodePairsSet.contains(pair)) {
            throw BinderException(
                stringFormat("Found duplicate FROM-TO {}-{} pairs.", srcTableName, dstTableName));
        }
        nodePairsSet.insert(pair);
        nodePairs.emplace_back(pair);
    }
    auto boundExtraInfo =
        std::make_unique<BoundExtraCreateRelTableGroupInfo>(std::move(propertyDefinitions),
            srcMultiplicity, dstMultiplicity, storageDirection, std::move(nodePairs));
    return BoundCreateTableInfo(CatalogEntryType::REL_GROUP_ENTRY, info->tableName,
        info->onConflict, std::move(boundExtraInfo), clientContext->useInternalCatalogEntry());
}

std::unique_ptr<BoundStatement> Binder::bindCreateTable(const Statement& statement) {
    auto& createTable = statement.constCast<CreateTable>();
    if (createTable.getSource()) {
        return bindCreateTableAs(createTable);
    }
    auto boundCreateInfo = bindCreateTableInfo(createTable.getInfo());
    return std::make_unique<BoundCreateTable>(std::move(boundCreateInfo),
        BoundStatementResult::createSingleStringColumnResult());
}

std::unique_ptr<BoundStatement> Binder::bindCreateTableAs(const Statement& statement) {
    auto& createTable = statement.constCast<CreateTable>();
    auto boundInnerQuery = bindQuery(*createTable.getSource()->statement.get());
    auto innerQueryResult = boundInnerQuery->getStatementResult();
    auto columnNames = innerQueryResult->getColumnNames();
    auto columnTypes = innerQueryResult->getColumnTypes();
    std::vector<PropertyDefinition> propertyDefinitions;
    propertyDefinitions.reserve(columnNames.size());
    for (size_t i = 0; i < columnNames.size(); ++i) {
        propertyDefinitions.emplace_back(
            ColumnDefinition(std::string(columnNames[i]), columnTypes[i].copy()));
    }
    if (columnNames.empty()) {
        throw BinderException("Subquery returns no columns");
    }
    auto createInfo = createTable.getInfo();
    switch (createInfo->type) {
    case TableType::NODE: {
        // first column is primary key column temporarily for now
        auto pkName = columnNames[0];
        validatePrimaryKey(pkName, propertyDefinitions);
        auto boundCopyFromInfo = bindCopyNodeFromInfo(createInfo->tableName, propertyDefinitions,
            createTable.getSource(), options_t{}, columnNames, columnTypes, false /* byColumn */);
        auto boundExtraInfo =
            std::make_unique<BoundExtraCreateNodeTableInfo>(pkName, std::move(propertyDefinitions));
        auto boundCreateInfo = BoundCreateTableInfo(CatalogEntryType::NODE_TABLE_ENTRY,
            createInfo->tableName, createInfo->onConflict, std::move(boundExtraInfo),
            clientContext->useInternalCatalogEntry());
        auto boundCreateTable = std::make_unique<BoundCreateTable>(std::move(boundCreateInfo),
            BoundStatementResult::createSingleStringColumnResult());
        boundCreateTable->setCopyInfo(std::move(boundCopyFromInfo));
        return boundCreateTable;
    }
    case TableType::REL: {
        auto& extraInfo = createInfo->extraInfo->constCast<ExtraCreateRelTableGroupInfo>();
        // Currently we don't support multiple from/to pairs for create rel table as
        if (extraInfo.srcDstTablePairs.size() > 1) {
            throw BinderException(
                "Multiple FROM/TO pairs are not supported for CREATE REL TABLE AS.");
        }
        propertyDefinitions.insert(propertyDefinitions.begin(),
            PropertyDefinition(ColumnDefinition(InternalKeyword::ID, LogicalType::INTERNAL_ID())));
        auto catalog = Catalog::Get(*clientContext);
        auto transaction = transaction::Transaction::Get(*clientContext);
        auto fromTable =
            catalog->getTableCatalogEntry(transaction, extraInfo.srcDstTablePairs[0].first)
                ->ptrCast<NodeTableCatalogEntry>();
        auto toTable =
            catalog->getTableCatalogEntry(transaction, extraInfo.srcDstTablePairs[0].second)
                ->ptrCast<NodeTableCatalogEntry>();
        auto boundCreateInfo = bindCreateRelTableGroupInfo(createInfo);
        auto boundCopyFromInfo = bindCopyRelFromInfo(createInfo->tableName, propertyDefinitions,
            createTable.getSource(), options_t{}, columnNames, columnTypes, fromTable, toTable);
        boundCreateInfo.extraInfo->ptrCast<BoundExtraCreateTableInfo>()->propertyDefinitions =
            std::move(propertyDefinitions);
        auto boundCreateTable = std::make_unique<BoundCreateTable>(std::move(boundCreateInfo),
            BoundStatementResult::createSingleStringColumnResult());
        boundCreateTable->setCopyInfo(std::move(boundCopyFromInfo));
        return boundCreateTable;
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

std::unique_ptr<BoundStatement> Binder::bindCreateType(const Statement& statement) const {
    auto createType = statement.constPtrCast<CreateType>();
    auto name = createType->getName();
    LogicalType type = LogicalType::convertFromString(createType->getDataType(), clientContext);
    auto transaction = transaction::Transaction::Get(*clientContext);
    if (Catalog::Get(*clientContext)->containsType(transaction, name)) {
        throw BinderException{stringFormat("Duplicated type name: {}.", name)};
    }
    return std::make_unique<BoundCreateType>(std::move(name), std::move(type));
}

std::unique_ptr<BoundStatement> Binder::bindCreateSequence(const Statement& statement) const {
    auto& createSequence = statement.constCast<CreateSequence>();
    auto info = createSequence.getInfo();
    auto sequenceName = info.sequenceName;
    int64_t startWith = 0;
    int64_t increment = 0;
    int64_t minValue = 0;
    int64_t maxValue = 0;
    auto transaction = transaction::Transaction::Get(*clientContext);
    switch (info.onConflict) {
    case ConflictAction::ON_CONFLICT_THROW: {
        if (Catalog::Get(*clientContext)->containsSequence(transaction, sequenceName)) {
            throw BinderException(sequenceName + " already exists in catalog.");
        }
    } break;
    default:
        break;
    }
    auto literal = ku_string_t{info.increment.c_str(), info.increment.length()};
    if (!function::CastString::tryCast(literal, increment)) {
        throw BinderException("Out of bounds: SEQUENCE accepts integers within INT64.");
    }
    if (increment == 0) {
        throw BinderException("INCREMENT must be non-zero.");
    }

    if (info.minValue == "") {
        minValue = increment > 0 ? 1 : std::numeric_limits<int64_t>::min();
    } else {
        literal = ku_string_t{info.minValue.c_str(), info.minValue.length()};
        if (!function::CastString::tryCast(literal, minValue)) {
            throw BinderException("Out of bounds: SEQUENCE accepts integers within INT64.");
        }
    }
    if (info.maxValue == "") {
        maxValue = increment > 0 ? std::numeric_limits<int64_t>::max() : -1;
    } else {
        literal = ku_string_t{info.maxValue.c_str(), info.maxValue.length()};
        if (!function::CastString::tryCast(literal, maxValue)) {
            throw BinderException("Out of bounds: SEQUENCE accepts integers within INT64.");
        }
    }
    if (info.startWith == "") {
        startWith = increment > 0 ? minValue : maxValue;
    } else {
        literal = ku_string_t{info.startWith.c_str(), info.startWith.length()};
        if (!function::CastString::tryCast(literal, startWith)) {
            throw BinderException("Out of bounds: SEQUENCE accepts integers within INT64.");
        }
    }

    if (maxValue < minValue) {
        throw BinderException("SEQUENCE MAXVALUE should be greater than or equal to MINVALUE.");
    }
    if (startWith < minValue || startWith > maxValue) {
        throw BinderException("SEQUENCE START value should be between MINVALUE and MAXVALUE.");
    }

    auto boundInfo = BoundCreateSequenceInfo(sequenceName, startWith, increment, minValue, maxValue,
        info.cycle, info.onConflict, false /* isInternal */);
    return std::make_unique<BoundCreateSequence>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindDrop(const Statement& statement) {
    auto& drop = statement.constCast<Drop>();
    return std::make_unique<BoundDrop>(drop.getDropInfo());
}

std::unique_ptr<BoundStatement> Binder::bindAlter(const Statement& statement) {
    auto& alter = statement.constCast<Alter>();
    switch (alter.getInfo()->type) {
    case AlterType::RENAME: {
        return bindRenameTable(statement);
    }
    case AlterType::ADD_PROPERTY: {
        return bindAddProperty(statement);
    }
    case AlterType::DROP_PROPERTY: {
        return bindDropProperty(statement);
    }
    case AlterType::RENAME_PROPERTY: {
        return bindRenameProperty(statement);
    }
    case AlterType::COMMENT: {
        return bindCommentOn(statement);
    }
    case AlterType::ADD_FROM_TO_CONNECTION:
    case AlterType::DROP_FROM_TO_CONNECTION: {
        return bindAlterFromToConnection(statement);
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

std::unique_ptr<BoundStatement> Binder::bindRenameTable(const Statement& statement) const {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = ku_dynamic_cast<ExtraRenameTableInfo*>(info->extraInfo.get());
    auto tableName = info->tableName;
    auto newName = extraInfo->newName;
    auto boundExtraInfo = std::make_unique<BoundExtraRenameTableInfo>(newName);
    auto boundInfo =
        BoundAlterInfo(AlterType::RENAME, tableName, std::move(boundExtraInfo), info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindAddProperty(const Statement& statement) {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = info->extraInfo->ptrCast<ExtraAddPropertyInfo>();
    auto tableName = info->tableName;
    auto propertyName = extraInfo->propertyName;
    auto type = LogicalType::convertFromString(extraInfo->dataType, clientContext);
    auto columnDefinition = ColumnDefinition(propertyName, type.copy());
    auto defaultExpr =
        resolvePropertyDefault(extraInfo->defaultValue.get(), type, tableName, propertyName);
    auto boundDefault = expressionBinder.bindExpression(*defaultExpr);
    boundDefault = expressionBinder.implicitCastIfNecessary(boundDefault, type);
    if (ConstantExpressionVisitor::needFold(*boundDefault)) {
        boundDefault = expressionBinder.foldExpression(boundDefault);
    }
    auto propertyDefinition =
        PropertyDefinition(std::move(columnDefinition), std::move(defaultExpr));
    auto boundExtraInfo = std::make_unique<BoundExtraAddPropertyInfo>(std::move(propertyDefinition),
        std::move(boundDefault));
    auto boundInfo = BoundAlterInfo(AlterType::ADD_PROPERTY, tableName, std::move(boundExtraInfo),
        info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindDropProperty(const Statement& statement) const {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = info->extraInfo->constPtrCast<ExtraDropPropertyInfo>();
    auto tableName = info->tableName;
    auto propertyName = extraInfo->propertyName;
    auto boundExtraInfo = std::make_unique<BoundExtraDropPropertyInfo>(propertyName);
    auto boundInfo = BoundAlterInfo(AlterType::DROP_PROPERTY, tableName, std::move(boundExtraInfo),
        info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindRenameProperty(const Statement& statement) const {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = info->extraInfo->constPtrCast<ExtraRenamePropertyInfo>();
    auto tableName = info->tableName;
    auto propertyName = extraInfo->propertyName;
    auto newName = extraInfo->newName;
    auto boundExtraInfo = std::make_unique<BoundExtraRenamePropertyInfo>(newName, propertyName);
    auto boundInfo = BoundAlterInfo(AlterType::RENAME_PROPERTY, tableName,
        std::move(boundExtraInfo), info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindCommentOn(const Statement& statement) const {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = info->extraInfo->constPtrCast<ExtraCommentInfo>();
    auto tableName = info->tableName;
    auto comment = extraInfo->comment;
    auto boundExtraInfo = std::make_unique<BoundExtraCommentInfo>(comment);
    auto boundInfo =
        BoundAlterInfo(AlterType::COMMENT, tableName, std::move(boundExtraInfo), info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

std::unique_ptr<BoundStatement> Binder::bindAlterFromToConnection(
    const Statement& statement) const {
    auto& alter = statement.constCast<Alter>();
    auto info = alter.getInfo();
    auto extraInfo = info->extraInfo->constPtrCast<ExtraAddFromToConnection>();
    auto tableName = info->tableName;
    auto srcTableEntry = bindNodeTableEntry(extraInfo->srcTableName);
    auto dstTableEntry = bindNodeTableEntry(extraInfo->dstTableName);
    auto srcTableID = srcTableEntry->getTableID();
    auto dstTableID = dstTableEntry->getTableID();
    auto boundExtraInfo = std::make_unique<BoundExtraAlterFromToConnection>(srcTableID, dstTableID);
    auto boundInfo =
        BoundAlterInfo(info->type, tableName, std::move(boundExtraInfo), info->onConflict);
    return std::make_unique<BoundAlter>(std::move(boundInfo));
}

} // namespace binder
} // namespace lbug
