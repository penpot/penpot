#pragma once

#include <mutex>
#include <shared_mutex>

#include "catalog_entry/catalog_entry.h"
#include "common/case_insensitive_map.h"

namespace lbug {
namespace binder {
struct BoundAlterInfo;
} // namespace binder

namespace storage {
class UndoBuffer;
} // namespace storage

namespace transaction {
class Transaction;
} // namespace transaction

using CatalogEntrySet = common::case_insensitive_map_t<catalog::CatalogEntry*>;

namespace catalog {
class LBUG_API CatalogSet {
    friend class storage::UndoBuffer;

public:
    CatalogSet() = default;
    explicit CatalogSet(bool isInternal);
    bool containsEntry(const transaction::Transaction* transaction, const std::string& name);
    CatalogEntry* getEntry(const transaction::Transaction* transaction, const std::string& name);
    common::oid_t createEntry(transaction::Transaction* transaction,
        std::unique_ptr<CatalogEntry> entry);
    void dropEntry(transaction::Transaction* transaction, const std::string& name,
        common::oid_t oid);

    void alterTableEntry(transaction::Transaction* transaction,
        const binder::BoundAlterInfo& alterInfo);

    CatalogEntrySet getEntries(const transaction::Transaction* transaction);
    CatalogEntry* getEntryOfOID(const transaction::Transaction* transaction, common::oid_t oid);

    void serialize(common::Serializer serializer) const;
    static std::unique_ptr<CatalogSet> deserialize(common::Deserializer& deserializer);

    common::oid_t getNextOID() {
        std::unique_lock lck{mtx};
        return nextOID++;
    }

    common::oid_t getNextOIDNoLock() { return nextOID++; }

private:
    bool containsEntryNoLock(const transaction::Transaction* transaction,
        const std::string& name) const;
    CatalogEntry* getEntryNoLock(const transaction::Transaction* transaction,
        const std::string& name) const;
    CatalogEntry* createEntryNoLock(const transaction::Transaction* transaction,
        std::unique_ptr<CatalogEntry> entry);
    CatalogEntry* dropEntryNoLock(const transaction::Transaction* transaction,
        const std::string& name, common::oid_t oid);

    void validateExistNoLock(const transaction::Transaction* transaction,
        const std::string& name) const;
    void validateNotExistNoLock(const transaction::Transaction* transaction,
        const std::string& name) const;

    void emplaceNoLock(std::unique_ptr<CatalogEntry> entry);
    void eraseNoLock(const std::string& name);

    static std::unique_ptr<CatalogEntry> createDummyEntryNoLock(std::string name,
        common::oid_t oid);

    static CatalogEntry* traverseVersionChainsForTransactionNoLock(
        const transaction::Transaction* transaction, CatalogEntry* currentEntry);
    static CatalogEntry* getCommittedEntryNoLock(CatalogEntry* entry);
    bool isInternal() const { return nextOID >= INTERNAL_CATALOG_SET_START_OID; }

public:
    // To ensure the uniqueness of the OID and avoid conflict with user tables/sequence, we make the
    // start OID of the internal catalog set to be 2^63.
    static constexpr common::oid_t INTERNAL_CATALOG_SET_START_OID = 1LL << 63;

private:
    std::shared_mutex mtx;
    common::oid_t nextOID = 0;
    common::case_insensitive_map_t<std::unique_ptr<CatalogEntry>> entries;
};

} // namespace catalog
} // namespace lbug
