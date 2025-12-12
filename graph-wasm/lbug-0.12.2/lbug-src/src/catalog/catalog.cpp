#include "catalog/catalog.h"

#include "binder/ddl/bound_create_sequence_info.h"
#include "binder/ddl/bound_create_table_info.h"
#include "catalog/catalog_entry/function_catalog_entry.h"
#include "catalog/catalog_entry/index_catalog_entry.h"
#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "catalog/catalog_entry/type_catalog_entry.h"
#include "common/exception/catalog.h"
#include "common/exception/runtime.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/string_format.h"
#include "extension/extension_manager.h"
#include "function/function_collection.h"
#include "main/client_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace catalog {

Catalog::Catalog() : version{0} {
    initCatalogSets();
    registerBuiltInFunctions();
}

Catalog* Catalog::Get(const main::ClientContext& context) {
    if (context.getAttachedDatabase()) {
        return context.getAttachedDatabase()->getCatalog();
    } else {
        return context.getDatabase()->getCatalog();
    }
}

void Catalog::initCatalogSets() {
    tables = std::make_unique<CatalogSet>();
    sequences = std::make_unique<CatalogSet>();
    functions = std::make_unique<CatalogSet>();
    types = std::make_unique<CatalogSet>();
    indexes = std::make_unique<CatalogSet>();
    macros = std::make_unique<CatalogSet>();
    internalTables = std::make_unique<CatalogSet>(true /* isInternal */);
    internalSequences = std::make_unique<CatalogSet>(true /* isInternal */);
    internalFunctions = std::make_unique<CatalogSet>(true /* isInternal */);
}

bool Catalog::containsTable(const Transaction* transaction, const std::string& tableName,
    bool useInternal) const {
    if (tables->containsEntry(transaction, tableName)) {
        return true;
    }
    if (useInternal) {
        return internalTables->containsEntry(transaction, tableName);
    }
    return false;
}

bool Catalog::containsTable(const Transaction* transaction, table_id_t tableID,
    bool useInternal) const {
    if (tables->getEntryOfOID(transaction, tableID) != nullptr) {
        return true;
    }
    if (useInternal) {
        return internalTables->getEntryOfOID(transaction, tableID) != nullptr;
    }
    return false;
}

TableCatalogEntry* Catalog::getTableCatalogEntry(const Transaction* transaction,
    table_id_t tableID) const {
    auto result = tables->getEntryOfOID(transaction, tableID);
    if (result == nullptr) {
        result = internalTables->getEntryOfOID(transaction, tableID);
    }
    // LCOV_EXCL_START
    if (result == nullptr) {
        throw RuntimeException(
            stringFormat("Cannot find table catalog entry with id {}.", std::to_string(tableID)));
    }
    // LCOV_EXCL_STOP
    return result->ptrCast<TableCatalogEntry>();
}

TableCatalogEntry* Catalog::getTableCatalogEntry(const Transaction* transaction,
    const std::string& tableName, bool useInternal) const {
    CatalogEntry* result = nullptr;
    if (!tables->containsEntry(transaction, tableName)) {
        if (!useInternal) {
            throw CatalogException(stringFormat("{} does not exist in catalog.", tableName));
        } else {
            result = internalTables->getEntry(transaction, tableName);
        }
    } else {
        result = tables->getEntry(transaction, tableName);
    }
    // LCOV_EXCL_STOP
    return result->ptrCast<TableCatalogEntry>();
}

template<TableCatalogEntryType T>
std::vector<T*> Catalog::getTableEntries(const Transaction* transaction, bool useInternal,
    CatalogEntryType entryType) const {
    std::vector<T*> result;
    for (auto& [_, entry] : tables->getEntries(transaction)) {
        if (entry->getType() != entryType) {
            continue;
        }
        result.push_back(entry->template ptrCast<T>());
    }
    if (useInternal) {
        for (auto& [_, entry] : internalTables->getEntries(transaction)) {
            if (entry->getType() != entryType) {
                continue;
            }
            result.push_back(entry->template ptrCast<T>());
        }
    }
    return result;
}

std::vector<NodeTableCatalogEntry*> Catalog::getNodeTableEntries(const Transaction* transaction,
    bool useInternal) const {
    return getTableEntries<NodeTableCatalogEntry>(transaction, useInternal,
        CatalogEntryType::NODE_TABLE_ENTRY);
}

std::vector<RelGroupCatalogEntry*> Catalog::getRelGroupEntries(const Transaction* transaction,
    bool useInternal) const {
    return getTableEntries<RelGroupCatalogEntry>(transaction, useInternal,
        CatalogEntryType::REL_GROUP_ENTRY);
}

std::vector<TableCatalogEntry*> Catalog::getTableEntries(const Transaction* transaction,
    bool useInternal) const {
    std::vector<TableCatalogEntry*> result;
    for (auto& [_, entry] : tables->getEntries(transaction)) {
        result.push_back(entry->ptrCast<TableCatalogEntry>());
    }
    if (useInternal) {
        for (auto& [_, entry] : internalTables->getEntries(transaction)) {
            result.push_back(entry->ptrCast<TableCatalogEntry>());
        }
    }
    return result;
}

void Catalog::dropTableEntryAndIndex(Transaction* transaction, const std::string& name) {
    auto tableID = getTableCatalogEntry(transaction, name)->getTableID();
    dropAllIndexes(transaction, tableID);
    dropTableEntry(transaction, tableID);
}

void Catalog::dropTableEntry(Transaction* transaction, table_id_t tableID) {
    dropTableEntry(transaction, getTableCatalogEntry(transaction, tableID));
}

void Catalog::dropTableEntry(Transaction* transaction, const TableCatalogEntry* entry) {
    dropSerialSequence(transaction, entry);
    if (tables->containsEntry(transaction, entry->getName())) {
        tables->dropEntry(transaction, entry->getName(), entry->getOID());
    } else {
        internalTables->dropEntry(transaction, entry->getName(), entry->getOID());
    }
}
void Catalog::dropMacroEntry(Transaction* transaction, const lbug::common::oid_t macroID) {
    dropMacroEntry(transaction, getScalarMacroCatalogEntry(transaction, macroID));
}

void Catalog::dropMacroEntry(Transaction* transaction, const ScalarMacroCatalogEntry* entry) {
    macros->dropEntry(transaction, entry->getName(), entry->getOID());
}

void Catalog::alterTableEntry(Transaction* transaction, const BoundAlterInfo& info) {
    tables->alterTableEntry(transaction, info);
}

CatalogEntry* Catalog::createRelGroupEntry(Transaction* transaction,
    const BoundCreateTableInfo& info) {
    const auto extraInfo = info.extraInfo->ptrCast<BoundExtraCreateRelTableGroupInfo>();
    std::vector<RelTableCatalogInfo> relTableInfos;
    KU_ASSERT(extraInfo->nodePairs.size() > 0);
    for (auto& nodePair : extraInfo->nodePairs) {
        relTableInfos.emplace_back(nodePair, tables->getNextOID());
    }
    auto relGroupEntry =
        std::make_unique<RelGroupCatalogEntry>(info.tableName, extraInfo->srcMultiplicity,
            extraInfo->dstMultiplicity, extraInfo->storageDirection, std::move(relTableInfos));
    for (auto& definition : extraInfo->propertyDefinitions) {
        relGroupEntry->addProperty(definition);
    }
    KU_ASSERT(info.hasParent == false);
    relGroupEntry->setHasParent(info.hasParent);
    createSerialSequence(transaction, relGroupEntry.get(), info.isInternal);
    auto catalogSet = info.isInternal ? internalTables.get() : tables.get();
    catalogSet->createEntry(transaction, std::move(relGroupEntry));
    return catalogSet->getEntry(transaction, info.tableName);
}

bool Catalog::containsSequence(const Transaction* transaction, const std::string& name) const {
    return sequences->containsEntry(transaction, name);
}

SequenceCatalogEntry* Catalog::getSequenceEntry(const Transaction* transaction,
    const std::string& sequenceName, bool useInternalSeq) const {
    CatalogEntry* entry = nullptr;
    if (!sequences->containsEntry(transaction, sequenceName) && useInternalSeq) {
        entry = internalSequences->getEntry(transaction, sequenceName);
    } else {
        entry = sequences->getEntry(transaction, sequenceName);
    }
    KU_ASSERT(entry);
    return entry->ptrCast<SequenceCatalogEntry>();
}

SequenceCatalogEntry* Catalog::getSequenceEntry(const Transaction* transaction,
    sequence_id_t sequenceID) const {
    auto entry = internalSequences->getEntryOfOID(transaction, sequenceID);
    if (entry == nullptr) {
        entry = sequences->getEntryOfOID(transaction, sequenceID);
    }
    KU_ASSERT(entry);
    return entry->ptrCast<SequenceCatalogEntry>();
}

std::vector<SequenceCatalogEntry*> Catalog::getSequenceEntries(
    const Transaction* transaction) const {
    std::vector<SequenceCatalogEntry*> result;
    for (auto& [_, entry] : sequences->getEntries(transaction)) {
        result.push_back(entry->ptrCast<SequenceCatalogEntry>());
    }
    return result;
}

sequence_id_t Catalog::createSequence(Transaction* transaction,
    const BoundCreateSequenceInfo& info) {
    auto entry = std::make_unique<SequenceCatalogEntry>(info);
    entry->setHasParent(info.hasParent);
    if (info.isInternal) {
        return internalSequences->createEntry(transaction, std::move(entry));
    } else {
        return sequences->createEntry(transaction, std::move(entry));
    }
}

void Catalog::dropSequence(Transaction* transaction, const std::string& name) {
    const auto entry = getSequenceEntry(transaction, name);
    dropSequence(transaction, entry->getOID());
}

void Catalog::dropSequence(Transaction* transaction, sequence_id_t sequenceID) {
    const auto sequenceEntry = getSequenceEntry(transaction, sequenceID);
    CatalogSet* set = nullptr;
    set = sequences->containsEntry(transaction, sequenceEntry->getName()) ? sequences.get() :
                                                                            internalSequences.get();
    set->dropEntry(transaction, sequenceEntry->getName(), sequenceEntry->getOID());
}

void Catalog::createType(Transaction* transaction, std::string name, LogicalType type) {
    if (types->containsEntry(transaction, name)) {
        return;
    }
    auto entry = std::make_unique<TypeCatalogEntry>(std::move(name), std::move(type));
    types->createEntry(transaction, std::move(entry));
}

static std::string getInstallExtensionMessage(std::string_view extensionName,
    std::string_view entryType) {
    return stringFormat("This {} exists in the {} "
                        "extension. You can install and load the "
                        "extension by running 'INSTALL {}; LOAD EXTENSION {};'.",
        entryType, extensionName, extensionName, extensionName);
}

static std::string getTypeDoesNotExistMessage(std::string_view entryName) {
    std::string message =
        stringFormat("{} is neither an internal type nor a user defined type.", entryName);
    const auto matchingExtensionFunction =
        extension::ExtensionManager::lookupExtensionsByTypeName(entryName);
    if (matchingExtensionFunction.has_value()) {
        message = stringFormat("{} {}", message,
            getInstallExtensionMessage(matchingExtensionFunction->extensionName, "type"));
    }
    return message;
}

LogicalType Catalog::getType(const Transaction* transaction, const std::string& name) const {
    if (!types->containsEntry(transaction, name)) {
        throw CatalogException{getTypeDoesNotExistMessage(name)};
    }
    return types->getEntry(transaction, name)
        ->constCast<TypeCatalogEntry>()
        .getLogicalType()
        .copy();
}

bool Catalog::containsType(const Transaction* transaction, const std::string& typeName) const {
    return types->containsEntry(transaction, typeName);
}

void Catalog::createIndex(Transaction* transaction,
    std::unique_ptr<CatalogEntry> indexCatalogEntry) {
    KU_ASSERT(indexCatalogEntry->getType() == CatalogEntryType::INDEX_ENTRY);
    indexes->createEntry(transaction, std::move(indexCatalogEntry));
}

IndexCatalogEntry* Catalog::getIndex(const Transaction* transaction, table_id_t tableID,
    const std::string& indexName) const {
    auto internalName = IndexCatalogEntry::getInternalIndexName(tableID, indexName);
    return indexes->getEntry(transaction, internalName)->ptrCast<IndexCatalogEntry>();
}

std::vector<IndexCatalogEntry*> Catalog::getIndexEntries(const Transaction* transaction) const {
    std::vector<IndexCatalogEntry*> result;
    for (auto& [_, entry] : indexes->getEntries(transaction)) {
        result.push_back(entry->ptrCast<IndexCatalogEntry>());
    }
    return result;
}

std::vector<IndexCatalogEntry*> Catalog::getIndexEntries(const Transaction* transaction,
    table_id_t tableID) const {
    std::vector<IndexCatalogEntry*> result;
    for (auto& [_, entry] : indexes->getEntries(transaction)) {
        auto indexEntry = entry->ptrCast<IndexCatalogEntry>();
        if (indexEntry->getTableID() == tableID) {
            result.push_back(indexEntry);
        }
    }
    return result;
}

bool Catalog::containsIndex(const Transaction* transaction, table_id_t tableID,
    const std::string& indexName) const {
    return indexes->containsEntry(transaction,
        IndexCatalogEntry::getInternalIndexName(tableID, indexName));
}

bool Catalog::containsIndex(const Transaction* transaction, table_id_t tableID,
    property_id_t propertyID) const {
    for (auto& [_, entry] : indexes->getEntries(transaction)) {
        auto indexEntry = entry->ptrCast<IndexCatalogEntry>();
        if (indexEntry->getTableID() != tableID) {
            continue;
        }
        if (indexEntry->containsPropertyID(propertyID)) {
            return true;
        }
    }
    return false;
}

bool Catalog::containsUnloadedIndex(const Transaction* transaction, common::table_id_t tableID,
    common::property_id_t propertyID) const {
    for (auto& [_, entry] : indexes->getEntries(transaction)) {
        auto indexEntry = entry->ptrCast<IndexCatalogEntry>();
        if (indexEntry->getTableID() != tableID || !indexEntry->containsPropertyID(propertyID)) {
            continue;
        }
        if (!indexEntry->isLoaded()) {
            return true;
        }
    }
    return false;
}

void Catalog::dropAllIndexes(Transaction* transaction, table_id_t tableID) {
    for (auto catalogEntry : indexes->getEntries(transaction)) {
        auto& indexCatalogEntry = catalogEntry.second->constCast<IndexCatalogEntry>();
        if (indexCatalogEntry.getTableID() == tableID) {
            indexes->dropEntry(transaction, catalogEntry.first, catalogEntry.second->getOID());
        }
    }
}

void Catalog::dropIndex(Transaction* transaction, table_id_t tableID,
    const std::string& indexName) const {
    auto uniqueName = IndexCatalogEntry::getInternalIndexName(tableID, indexName);
    const auto entry = indexes->getEntry(transaction, uniqueName);
    indexes->dropEntry(transaction, uniqueName, entry->getOID());
}

void Catalog::dropIndex(Transaction* transaction, oid_t indexOID) {
    const auto entry = indexes->getEntryOfOID(transaction, indexOID);
    if (entry == nullptr) {
        throw CatalogException{stringFormat("Index with OID {} does not exist.", indexOID)};
    }
    indexes->dropEntry(transaction, entry->getName(), indexOID);
}

bool Catalog::containsFunction(const Transaction* transaction, const std::string& name,
    bool useInternal) const {
    auto hasEntry = functions->containsEntry(transaction, name);
    if (!hasEntry && useInternal) {
        return internalFunctions->containsEntry(transaction, name);
    }
    return hasEntry;
}

void Catalog::addFunction(Transaction* transaction, CatalogEntryType entryType, std::string name,
    function::function_set functionSet, bool isInternal) {
    auto& catalogSet = isInternal ? internalFunctions : functions;
    if (catalogSet->containsEntry(transaction, name)) {
        throw CatalogException{stringFormat("function {} already exists.", name)};
    }
    catalogSet->createEntry(transaction,
        std::make_unique<FunctionCatalogEntry>(entryType, std::move(name), std::move(functionSet)));
}

static std::string getFunctionDoesNotExistMessage(std::string_view entryName) {
    std::string message = stringFormat("function {} does not exist.", entryName);
    const auto matchingExtensionFunction =
        extension::ExtensionManager::lookupExtensionsByFunctionName(entryName);
    if (matchingExtensionFunction.has_value()) {
        message = stringFormat("function {} is not defined. {}", entryName,
            getInstallExtensionMessage(matchingExtensionFunction->extensionName, "function"));
    }
    return message;
}

void Catalog::dropFunction(Transaction* transaction, const std::string& name) {
    if (!containsFunction(transaction, name)) {
        throw CatalogException{stringFormat("function {} doesn't exist.", name)};
    }
    auto entry = getFunctionEntry(transaction, name);
    functions->dropEntry(transaction, name, entry->getOID());
}

CatalogEntry* Catalog::getFunctionEntry(const Transaction* transaction, const std::string& name,
    bool useInternal) const {
    CatalogEntry* result = nullptr;
    if (functions->containsEntry(transaction, name)) {
        result = functions->getEntry(transaction, name);
    } else if (macros->containsEntry(transaction, name)) {
        result = macros->getEntry(transaction, name);
    } else if (useInternal) {
        result = internalFunctions->getEntry(transaction, name);
    } else {
        throw CatalogException(getFunctionDoesNotExistMessage(name));
    }
    return result;
}

std::vector<ScalarMacroCatalogEntry*> Catalog::getMacroEntries(
    const Transaction* transaction) const {
    std::vector<ScalarMacroCatalogEntry*> result;
    for (auto& [_, entry] : macros->getEntries(transaction)) {
        KU_ASSERT(entry->getType() == CatalogEntryType::SCALAR_MACRO_ENTRY);
        result.push_back(entry->ptrCast<ScalarMacroCatalogEntry>());
    }
    return result;
}

std::vector<FunctionCatalogEntry*> Catalog::getFunctionEntries(
    const Transaction* transaction) const {
    std::vector<FunctionCatalogEntry*> result;
    for (auto& [_, entry] : functions->getEntries(transaction)) {
        result.push_back(entry->ptrCast<FunctionCatalogEntry>());
    }
    return result;
}

bool Catalog::containsMacro(const Transaction* transaction, const std::string& macroName) const {
    return macros->containsEntry(transaction, macroName);
}

function::ScalarMacroFunction* Catalog::getScalarMacroFunction(const Transaction* transaction,
    const std::string& name) const {
    return macros->getEntry(transaction, name)
        ->constCast<ScalarMacroCatalogEntry>()
        .getMacroFunction();
}

// addScalarMacroFunction
void Catalog::addScalarMacroFunction(Transaction* transaction, std::string name,
    std::unique_ptr<function::ScalarMacroFunction> macro) {
    auto entry = std::make_unique<ScalarMacroCatalogEntry>(std::move(name), std::move(macro));
    macros->createEntry(transaction, std::move(entry));
}

ScalarMacroCatalogEntry* Catalog::getScalarMacroCatalogEntry(const Transaction* transaction,
    lbug::common::oid_t macroID) const {
    auto result = functions->getEntryOfOID(transaction, macroID);
    if (result == nullptr) {
        throw RuntimeException(
            stringFormat("Cannot find macro catalog entry with id {}.", std::to_string(macroID)));
    }

    return result->ptrCast<ScalarMacroCatalogEntry>();
}

std::vector<std::string> Catalog::getMacroNames(const Transaction* transaction) const {
    std::vector<std::string> macroNames;
    for (auto& [_, function] : macros->getEntries(transaction)) {
        KU_ASSERT(function->getType() == CatalogEntryType::SCALAR_MACRO_ENTRY);
        macroNames.push_back(function->getName());
    }
    return macroNames;
}

void Catalog::dropMacro(Transaction* transaction, std::string& name) {
    if (!containsMacro(transaction, name)) {
        throw CatalogException{stringFormat("Marco {} doesn't exist.", name)};
    }
    auto entry = getFunctionEntry(transaction, name);
    macros->dropEntry(transaction, name, entry->getOID());
}

void Catalog::registerBuiltInFunctions() {
    auto functionCollection = function::FunctionCollection::getFunctions();
    for (auto i = 0u; functionCollection[i].name != nullptr; ++i) {
        auto& f = functionCollection[i];
        auto functionSet = f.getFunctionSetFunc();
        functions->createEntry(&DUMMY_TRANSACTION,
            std::make_unique<FunctionCatalogEntry>(f.catalogEntryType, f.name,
                std::move(functionSet)));
    }
}

CatalogEntry* Catalog::createTableEntry(Transaction* transaction,
    const BoundCreateTableInfo& info) {
    switch (info.type) {
    case CatalogEntryType::NODE_TABLE_ENTRY: {
        return createNodeTableEntry(transaction, info);
    }
    case CatalogEntryType::REL_GROUP_ENTRY: {
        return createRelGroupEntry(transaction, info);
    }
    default:
        KU_UNREACHABLE;
    }
}

CatalogEntry* Catalog::createNodeTableEntry(Transaction* transaction,
    const BoundCreateTableInfo& info) {
    const auto extraInfo = info.extraInfo->constPtrCast<BoundExtraCreateNodeTableInfo>();
    auto entry = std::make_unique<NodeTableCatalogEntry>(info.tableName, extraInfo->primaryKeyName);
    for (auto& definition : extraInfo->propertyDefinitions) {
        entry->addProperty(definition);
    }
    entry->setHasParent(info.hasParent);
    createSerialSequence(transaction, entry.get(), info.isInternal);
    auto catalogSet = info.isInternal ? internalTables.get() : tables.get();
    catalogSet->createEntry(transaction, std::move(entry));
    return catalogSet->getEntry(transaction, info.tableName);
}

void Catalog::createSerialSequence(Transaction* transaction, const TableCatalogEntry* entry,
    bool isInternal) {
    for (auto& definition : entry->getProperties()) {
        if (definition.getType().getLogicalTypeID() != LogicalTypeID::SERIAL) {
            continue;
        }
        const auto seqName =
            SequenceCatalogEntry::getSerialName(entry->getName(), definition.getName());
        auto seqInfo =
            BoundCreateSequenceInfo(seqName, 0, 1, 0, std::numeric_limits<int64_t>::max(), false,
                ConflictAction::ON_CONFLICT_THROW, isInternal);
        seqInfo.hasParent = true;
        createSequence(transaction, seqInfo);
    }
}

void Catalog::dropSerialSequence(Transaction* transaction, const TableCatalogEntry* entry) {
    for (auto& definition : entry->getProperties()) {
        if (definition.getType().getLogicalTypeID() != LogicalTypeID::SERIAL) {
            continue;
        }
        auto seqName = SequenceCatalogEntry::getSerialName(entry->getName(), definition.getName());
        dropSequence(transaction, seqName);
    }
}

void Catalog::serialize(Serializer& ser) const {
    tables->serialize(ser);
    sequences->serialize(ser);
    functions->serialize(ser);
    types->serialize(ser);
    indexes->serialize(ser);
    macros->serialize(ser);
    internalTables->serialize(ser);
    internalSequences->serialize(ser);
    internalFunctions->serialize(ser);
}

void Catalog::deserialize(Deserializer& deSer) {
    tables = CatalogSet::deserialize(deSer);
    sequences = CatalogSet::deserialize(deSer);
    functions = CatalogSet::deserialize(deSer);
    registerBuiltInFunctions();
    types = CatalogSet::deserialize(deSer);
    indexes = CatalogSet::deserialize(deSer);
    macros = CatalogSet::deserialize(deSer);
    internalTables = CatalogSet::deserialize(deSer);
    internalSequences = CatalogSet::deserialize(deSer);
    internalFunctions = CatalogSet::deserialize(deSer);
}

} // namespace catalog
} // namespace lbug
