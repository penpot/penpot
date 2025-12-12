#include "catalog/catalog_set.h"

#include <mutex>

#include "binder/ddl/bound_alter_info.h"
#include "catalog/catalog_entry/dummy_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/assert.h"
#include "common/exception/catalog.h"
#include "common/serializer/deserializer.h"
#include "common/string_format.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace catalog {

CatalogSet::CatalogSet(bool isInternal) {
    if (isInternal) {
        nextOID = INTERNAL_CATALOG_SET_START_OID;
    }
}

static bool checkWWConflict(const Transaction* transaction, const CatalogEntry* entry) {
    return (entry->getTimestamp() >= Transaction::START_TRANSACTION_ID &&
               entry->getTimestamp() != transaction->getID()) ||
           (entry->getTimestamp() < Transaction::START_TRANSACTION_ID &&
               entry->getTimestamp() > transaction->getStartTS());
}

bool CatalogSet::containsEntry(const Transaction* transaction, const std::string& name) {
    std::shared_lock lck{mtx};
    return containsEntryNoLock(transaction, name);
}

bool CatalogSet::containsEntryNoLock(const Transaction* transaction,
    const std::string& name) const {
    if (!entries.contains(name)) {
        return false;
    }
    // Check versions.
    const auto entry =
        traverseVersionChainsForTransactionNoLock(transaction, entries.at(name).get());
    return !entry->isDeleted();
}

CatalogEntry* CatalogSet::getEntry(const Transaction* transaction, const std::string& name) {
    std::shared_lock lck{mtx};
    return getEntryNoLock(transaction, name);
}

CatalogEntry* CatalogSet::getEntryNoLock(const Transaction* transaction,
    const std::string& name) const {
    // LCOV_EXCL_START
    validateExistNoLock(transaction, name);
    // LCOV_EXCL_STOP
    const auto entry =
        traverseVersionChainsForTransactionNoLock(transaction, entries.at(name).get());
    KU_ASSERT(entry != nullptr && !entry->isDeleted());
    return entry;
}

oid_t CatalogSet::createEntry(Transaction* transaction, std::unique_ptr<CatalogEntry> entry) {
    CatalogEntry* entryPtr = nullptr;
    oid_t oid = INVALID_OID;
    {
        std::unique_lock lck{mtx};
        oid = nextOID++;
        entry->setOID(oid);
        entryPtr = createEntryNoLock(transaction, std::move(entry));
    }
    KU_ASSERT(entryPtr);
    if (transaction->shouldAppendToUndoBuffer()) {
        transaction->pushCreateDropCatalogEntry(*this, *entryPtr, isInternal());
    }
    return oid;
}

CatalogEntry* CatalogSet::createEntryNoLock(const Transaction* transaction,
    std::unique_ptr<CatalogEntry> entry) {
    // LCOV_EXCL_START
    validateNotExistNoLock(transaction, entry->getName());
    // LCOV_EXCL_STOP
    entry->setTimestamp(transaction->getID());
    if (entries.contains(entry->getName())) {
        const auto existingEntry = entries.at(entry->getName()).get();
        if (checkWWConflict(transaction, existingEntry)) {
            throw CatalogException(stringFormat(
                "Write-write conflict on creating catalog entry with name {}.", entry->getName()));
        }
        if (!existingEntry->isDeleted()) {
            throw CatalogException(
                stringFormat("Catalog entry with name {} already exists.", entry->getName()));
        }
    }
    auto dummyEntry = createDummyEntryNoLock(entry->getName(), entry->getOID());
    entries.emplace(entry->getName(), std::move(dummyEntry));
    const auto entryPtr = entry.get();
    emplaceNoLock(std::move(entry));
    return entryPtr->getPrev();
}

void CatalogSet::emplaceNoLock(std::unique_ptr<CatalogEntry> entry) {
    if (entries.contains(entry->getName())) {
        entry->setPrev(std::move(entries.at(entry->getName())));
        entries.erase(entry->getName());
    }
    entries.emplace(entry->getName(), std::move(entry));
}

void CatalogSet::eraseNoLock(const std::string& name) {
    entries.erase(name);
}

std::unique_ptr<CatalogEntry> CatalogSet::createDummyEntryNoLock(std::string name, oid_t oid) {
    return std::make_unique<DummyCatalogEntry>(std::move(name), oid);
}

CatalogEntry* CatalogSet::traverseVersionChainsForTransactionNoLock(const Transaction* transaction,
    CatalogEntry* currentEntry) {
    while (currentEntry) {
        if (currentEntry->getTimestamp() == transaction->getID()) {
            // This entry is created by the current transaction.
            break;
        }
        if (currentEntry->getTimestamp() <= transaction->getStartTS()) {
            // This entry was committed before the current transaction starts.
            break;
        }
        currentEntry = currentEntry->getPrev();
    }
    return currentEntry;
}

CatalogEntry* CatalogSet::getCommittedEntryNoLock(CatalogEntry* entry) {
    while (entry) {
        if (entry->getTimestamp() < Transaction::START_TRANSACTION_ID) {
            break;
        }
        entry = entry->getPrev();
    }
    return entry;
}

void CatalogSet::dropEntry(Transaction* transaction, const std::string& name, oid_t oid) {
    CatalogEntry* entryPtr = nullptr;
    {
        std::unique_lock lck{mtx};
        entryPtr = dropEntryNoLock(transaction, name, oid);
    }
    KU_ASSERT(entryPtr);
    if (transaction->shouldAppendToUndoBuffer()) {
        transaction->pushCreateDropCatalogEntry(*this, *entryPtr, isInternal());
    }
}

CatalogEntry* CatalogSet::dropEntryNoLock(const Transaction* transaction, const std::string& name,
    oid_t oid) {
    // LCOV_EXCL_START
    validateExistNoLock(transaction, name);
    // LCOV_EXCL_STOP
    auto tombstone = createDummyEntryNoLock(name, oid);
    tombstone->setTimestamp(transaction->getID());
    const auto tombstonePtr = tombstone.get();
    emplaceNoLock(std::move(tombstone));
    return tombstonePtr->getPrev();
}

void CatalogSet::alterTableEntry(Transaction* transaction,
    const binder::BoundAlterInfo& alterInfo) {
    std::unique_lock lck{mtx};
    // LCOV_EXCL_START
    validateExistNoLock(transaction, alterInfo.tableName);
    // LCOV_EXCL_STOP
    auto entry = getEntryNoLock(transaction, alterInfo.tableName);
    KU_ASSERT(entry->getType() == CatalogEntryType::NODE_TABLE_ENTRY ||
              entry->getType() == CatalogEntryType::REL_GROUP_ENTRY);
    const auto tableEntry = entry->ptrCast<TableCatalogEntry>();
    auto newEntry = tableEntry->alter(transaction->getID(), alterInfo, this);
    switch (alterInfo.alterType) {
    case AlterType::RENAME: {
        // We treat rename table as drop and create.
        dropEntryNoLock(transaction, alterInfo.tableName, entry->getOID());
        auto createdEntry = createEntryNoLock(transaction, std::move(newEntry));
        if (transaction->shouldAppendToUndoBuffer()) {
            transaction->pushAlterCatalogEntry(*this, *entry, alterInfo);
            transaction->pushCreateDropCatalogEntry(*this, *createdEntry, isInternal(),
                true /* skipLoggingToWAL */);
        }
    } break;
    case AlterType::COMMENT:
    case AlterType::ADD_PROPERTY:
    case AlterType::DROP_PROPERTY:
    case AlterType::RENAME_PROPERTY:
    case AlterType::ADD_FROM_TO_CONNECTION:
    case AlterType::DROP_FROM_TO_CONNECTION: {
        emplaceNoLock(std::move(newEntry));
        if (transaction->shouldAppendToUndoBuffer()) {
            transaction->pushAlterCatalogEntry(*this, *entry, alterInfo);
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

CatalogEntrySet CatalogSet::getEntries(const Transaction* transaction) {
    CatalogEntrySet result;
    std::shared_lock lck{mtx};
    for (auto& [name, entry] : entries) {
        auto currentEntry = traverseVersionChainsForTransactionNoLock(transaction, entry.get());
        if (currentEntry->isDeleted()) {
            continue;
        }
        result.emplace(name, currentEntry);
    }
    return result;
}

CatalogEntry* CatalogSet::getEntryOfOID(const Transaction* transaction, oid_t oid) {
    std::shared_lock lck{mtx};
    for (auto& [_, entry] : entries) {
        if (entry->getOID() != oid) {
            continue;
        }
        const auto currentEntry =
            traverseVersionChainsForTransactionNoLock(transaction, entry.get());
        if (currentEntry->isDeleted()) {
            continue;
        }
        return currentEntry;
    }
    return nullptr;
}

void CatalogSet::serialize(Serializer serializer) const {
    std::vector<CatalogEntry*> entriesToSerialize;
    for (auto& [_, entry] : entries) {
        switch (entry->getType()) {
        case CatalogEntryType::SCALAR_FUNCTION_ENTRY:
        case CatalogEntryType::REWRITE_FUNCTION_ENTRY:
        case CatalogEntryType::AGGREGATE_FUNCTION_ENTRY:
        case CatalogEntryType::COPY_FUNCTION_ENTRY:
        case CatalogEntryType::TABLE_FUNCTION_ENTRY:
        case CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY:
            continue;
        default: {
            auto committedEntry = getCommittedEntryNoLock(entry.get());
            if (committedEntry && !committedEntry->isDeleted()) {
                entriesToSerialize.push_back(committedEntry);
            }
        }
        }
    }
    serializer.writeDebuggingInfo("nextOID");
    serializer.serializeValue<oid_t>(nextOID);
    serializer.writeDebuggingInfo("numEntries");
    const uint64_t numEntriesToSerialize = entriesToSerialize.size();
    serializer.serializeValue<uint64_t>(numEntriesToSerialize);
    for (const auto entry : entriesToSerialize) {
        entry->serialize(serializer);
    }
}

std::unique_ptr<CatalogSet> CatalogSet::deserialize(Deserializer& deserializer) {
    std::string debuggingInfo;
    auto catalogSet = std::make_unique<CatalogSet>();
    deserializer.validateDebuggingInfo(debuggingInfo, "nextOID");
    deserializer.deserializeValue<oid_t>(catalogSet->nextOID);
    uint64_t numEntries = 0;
    deserializer.validateDebuggingInfo(debuggingInfo, "numEntries");
    deserializer.deserializeValue<uint64_t>(numEntries);
    for (uint64_t i = 0; i < numEntries; i++) {
        auto entry = CatalogEntry::deserialize(deserializer);
        if (entry != nullptr) {
            catalogSet->emplaceNoLock(std::move(entry));
        }
    }
    return catalogSet;
}

// Ideally we should not trigger the following check. Instead, we should throw more informative
// error message at catalog level.
void CatalogSet::validateExistNoLock(const Transaction* transaction,
    const std::string& name) const {
    if (!containsEntryNoLock(transaction, name)) {
        throw CatalogException(stringFormat("{} does not exist in catalog.", name));
    }
}

void CatalogSet::validateNotExistNoLock(const Transaction* transaction,
    const std::string& name) const {
    if (containsEntryNoLock(transaction, name)) {
        throw CatalogException(stringFormat("{} already exists in catalog.", name));
    }
}

} // namespace catalog
} // namespace lbug
