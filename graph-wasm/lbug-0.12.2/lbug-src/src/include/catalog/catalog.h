#pragma once

#include "catalog/catalog_entry/function_catalog_entry.h"
#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"
#include "catalog/catalog_set.h"
#include "common/cast.h"
#include "function/function.h"

namespace lbug::main {
struct DBConfig;
} // namespace lbug::main

namespace lbug {
namespace main {
class AttachedLbugDatabase;
} // namespace main

namespace binder {
struct BoundAlterInfo;
struct BoundCreateTableInfo;
struct BoundCreateSequenceInfo;
} // namespace binder

namespace common {
class VirtualFileSystem;
} // namespace common

namespace function {
struct ScalarMacroFunction;
} // namespace function

namespace storage {
class WAL;
} // namespace storage

namespace transaction {
class Transaction;
} // namespace transaction

namespace catalog {
class TableCatalogEntry;
class NodeTableCatalogEntry;
class RelGroupCatalogEntry;
class FunctionCatalogEntry;
class SequenceCatalogEntry;
class IndexCatalogEntry;

template<typename T>
concept TableCatalogEntryType =
    std::is_same_v<T, NodeTableCatalogEntry> || std::is_same_v<T, RelGroupCatalogEntry>;

class LBUG_API Catalog {
    friend class main::AttachedLbugDatabase;

public:
    Catalog();
    virtual ~Catalog() = default;

    static Catalog* Get(const main::ClientContext& context);

    // ----------------------------- Tables ----------------------------

    // Check if table entry exists.
    bool containsTable(const transaction::Transaction* transaction, const std::string& tableName,
        bool useInternal = true) const;
    bool containsTable(const transaction::Transaction* transaction, common::table_id_t tableID,
        bool useInternal = true) const;
    // Get table entry with name.
    TableCatalogEntry* getTableCatalogEntry(const transaction::Transaction* transaction,
        const std::string& tableName, bool useInternal = true) const;
    // Get table entry with id.
    TableCatalogEntry* getTableCatalogEntry(const transaction::Transaction* transaction,
        common::table_id_t tableID) const;
    // Get all node table entries.
    std::vector<NodeTableCatalogEntry*> getNodeTableEntries(
        const transaction::Transaction* transaction, bool useInternal = true) const;
    // Get all rel table entries.
    std::vector<RelGroupCatalogEntry*> getRelGroupEntries(
        const transaction::Transaction* transaction, bool useInternal = true) const;
    // Get all table entries.
    std::vector<TableCatalogEntry*> getTableEntries(const transaction::Transaction* transaction,
        bool useInternal = true) const;

    // Create table catalog entry.
    CatalogEntry* createTableEntry(transaction::Transaction* transaction,
        const binder::BoundCreateTableInfo& info);
    // Drop table entry and all indices within the table.
    void dropTableEntryAndIndex(transaction::Transaction* transaction, const std::string& name);
    // Drop table entry with id.
    void dropTableEntry(transaction::Transaction* transaction, common::table_id_t tableID);
    // Drop table entry.
    void dropTableEntry(transaction::Transaction* transaction, const TableCatalogEntry* entry);
    // Alter table entry.
    void alterTableEntry(transaction::Transaction* transaction, const binder::BoundAlterInfo& info);

    // ----------------------------- Sequences ----------------------------

    // Check if sequence entry exists.
    bool containsSequence(const transaction::Transaction* transaction,
        const std::string& name) const;
    // Get sequence entry with name.
    SequenceCatalogEntry* getSequenceEntry(const transaction::Transaction* transaction,
        const std::string& sequenceName, bool useInternalSeq = true) const;
    // Get sequence entry with id.
    SequenceCatalogEntry* getSequenceEntry(const transaction::Transaction* transaction,
        common::sequence_id_t sequenceID) const;
    // Get all sequence entries.
    std::vector<SequenceCatalogEntry*> getSequenceEntries(
        const transaction::Transaction* transaction) const;

    // Create sequence entry.
    common::sequence_id_t createSequence(transaction::Transaction* transaction,
        const binder::BoundCreateSequenceInfo& info);
    // Drop sequence entry with name.
    void dropSequence(transaction::Transaction* transaction, const std::string& name);
    // Drop sequence entry with id.
    void dropSequence(transaction::Transaction* transaction, common::sequence_id_t sequenceID);

    // ----------------------------- Types ----------------------------

    // Check if type entry exists.
    bool containsType(const transaction::Transaction* transaction, const std::string& name) const;
    // Get type entry with name.
    common::LogicalType getType(const transaction::Transaction*, const std::string& name) const;

    // Create type entry.
    void createType(transaction::Transaction* transaction, std::string name,
        common::LogicalType type);

    // ----------------------------- Indexes ----------------------------

    // Check if index exists for given table and name
    bool containsIndex(const transaction::Transaction* transaction, common::table_id_t tableID,
        const std::string& indexName) const;
    // Check if index exists for given table and property
    bool containsIndex(const transaction::Transaction* transaction, common::table_id_t tableID,
        common::property_id_t propertyID) const;
    // Check if there is any unloaded index for given table and property
    bool containsUnloadedIndex(const transaction::Transaction* transaction,
        common::table_id_t tableID, common::property_id_t propertyID) const;
    // Get index entry with name.
    IndexCatalogEntry* getIndex(const transaction::Transaction* transaction,
        common::table_id_t tableID, const std::string& indexName) const;
    // Get all index entries.
    std::vector<IndexCatalogEntry*> getIndexEntries(
        const transaction::Transaction* transaction) const;
    // Get all index entries for given table
    std::vector<IndexCatalogEntry*> getIndexEntries(const transaction::Transaction* transaction,
        common::table_id_t tableID) const;

    // Create index entry.
    void createIndex(transaction::Transaction* transaction,
        std::unique_ptr<CatalogEntry> indexCatalogEntry);
    // Drop all index entries within a table.
    void dropAllIndexes(transaction::Transaction* transaction, common::table_id_t tableID);
    // Drop index entry with name.
    void dropIndex(transaction::Transaction* transaction, common::table_id_t tableID,
        const std::string& indexName) const;
    void dropIndex(transaction::Transaction* transaction, common::oid_t indexOID);

    // ----------------------------- Functions ----------------------------

    // Check if function exists.
    bool containsFunction(const transaction::Transaction* transaction, const std::string& name,
        bool useInternal = false) const;
    // Get function entry by name.
    // Note we cannot cast to FunctionEntry here because result could also be a MacroEntry.
    CatalogEntry* getFunctionEntry(const transaction::Transaction* transaction,
        const std::string& name, bool useInternal = false) const;
    // Get all function entries.
    std::vector<FunctionCatalogEntry*> getFunctionEntries(
        const transaction::Transaction* transaction) const;

    // Get all macro entries.
    std::vector<ScalarMacroCatalogEntry*> getMacroEntries(
        const transaction::Transaction* transaction) const;

    // Add function with name.
    void addFunction(transaction::Transaction* transaction, CatalogEntryType entryType,
        std::string name, function::function_set functionSet, bool isInternal = false);
    // Drop function with name.
    void dropFunction(transaction::Transaction* transaction, const std::string& name);

    // ----------------------------- Macro ----------------------------

    // Check if macro entry exists.
    bool containsMacro(const transaction::Transaction* transaction,
        const std::string& macroName) const;
    void addScalarMacroFunction(transaction::Transaction* transaction, std::string name,
        std::unique_ptr<function::ScalarMacroFunction> macro);
    ScalarMacroCatalogEntry* getScalarMacroCatalogEntry(const transaction::Transaction* transaction,
        lbug::common::oid_t MacroID) const;
    void dropMacroEntry(transaction::Transaction* transaction, const lbug::common::oid_t macroID);
    void dropMacroEntry(transaction::Transaction* transaction,
        const ScalarMacroCatalogEntry* entry);
    function::ScalarMacroFunction* getScalarMacroFunction(
        const transaction::Transaction* transaction, const std::string& name) const;
    std::vector<std::string> getMacroNames(const transaction::Transaction* transaction) const;
    void dropMacro(transaction::Transaction* transaction, std::string& name);

    void incrementVersion() { version++; }
    uint64_t getVersion() const { return version; }
    bool changedSinceLastCheckpoint() const { return version != 0; }
    void resetVersion() { version = 0; }

    void serialize(common::Serializer& ser) const;
    void deserialize(common::Deserializer& deSer);

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

private:
    void initCatalogSets();
    void registerBuiltInFunctions();

    CatalogEntry* createNodeTableEntry(transaction::Transaction* transaction,
        const binder::BoundCreateTableInfo& info);
    CatalogEntry* createRelGroupEntry(transaction::Transaction* transaction,
        const binder::BoundCreateTableInfo& info);

    void createSerialSequence(transaction::Transaction* transaction, const TableCatalogEntry* entry,
        bool isInternal);
    void dropSerialSequence(transaction::Transaction* transaction, const TableCatalogEntry* entry);

    template<TableCatalogEntryType T>
    std::vector<T*> getTableEntries(const transaction::Transaction* transaction, bool useInternal,
        CatalogEntryType entryType) const;

protected:
    std::unique_ptr<CatalogSet> tables;

private:
    std::unique_ptr<CatalogSet> sequences;
    std::unique_ptr<CatalogSet> functions;
    std::unique_ptr<CatalogSet> types;
    std::unique_ptr<CatalogSet> indexes;
    std::unique_ptr<CatalogSet> macros;
    std::unique_ptr<CatalogSet> internalTables;
    std::unique_ptr<CatalogSet> internalSequences;
    std::unique_ptr<CatalogSet> internalFunctions;

    // incremented whenever a change is made to the catalog
    // reset to 0 at the end of each checkpoint
    uint64_t version;
};

} // namespace catalog
} // namespace lbug
