#pragma once

#include <algorithm>
#include <string_view>
#include <type_traits>

#include "common/cast.h"
#include "common/serializer/buffer_reader.h"
#include "common/serializer/serializer.h"
#include "common/type_utils.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "hash_index_header.h"
#include "hash_index_slot.h"
#include "index.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/disk_array_collection.h"
#include "storage/index/hash_index_utils.h"
#include "storage/index/in_mem_hash_index.h"
#include "storage/local_storage/local_hash_index.h"

namespace lbug {
namespace common {
class VirtualFileSystem;
}
namespace transaction {
class Transaction;
enum class TransactionType : uint8_t;
} // namespace transaction
namespace storage {

class FileHandle;
class BufferManager;
class OverflowFileHandle;
template<typename T>
class DiskArray;
class PageManager;

class OnDiskHashIndex {
public:
    virtual ~OnDiskHashIndex() = default;
    virtual bool checkpoint(PageAllocator& pageAllocator) = 0;
    virtual bool checkpointInMemory() = 0;
    virtual bool rollbackInMemory() = 0;
    virtual void rollbackCheckpoint() = 0;
    virtual void bulkReserve(uint64_t numValuesToAppend) = 0;
    virtual void reclaimStorage(PageAllocator& pageAllocator) = 0;
    virtual bool tryLock() = 0;
    virtual std::unique_lock<std::shared_mutex> adoptLock() = 0;
};

// HashIndex is the entrance to handle all updates and lookups into the index after building from
// scratch through InMemHashIndex.
// The index consists of two parts, one is the persistent storage (from the persistent index file),
// and the other is the local storage. All lookups/deletions/insertions go through local storage,
// and then the persistent storage if necessary.
//
// Key interfaces:
// - lookup(): Given a key, find its result. Return true if the key is found, else, return false.
//   Lookups go through the local storage first, check if the key is marked as deleted or not, then
//   check whether it can be found inside local insertions or not. If the key is neither marked as
//   deleted nor found in local insertions, we proceed to lookups in the persistent store.
// - delete(): Delete the given key.
//   Deletions are directly marked in the local storage.
// - insert(): Insert the given key and value. Return true if the given key doesn't exist in the
// index before insertion, otherwise, return false.
//   First check if the key to be inserted already exists in local insertions or the persistent
//   store. If the key doesn't exist yet, append it to local insertions, and also remove it from
//   local deletions if it was marked as deleted.
//
// T is the key type used to access values
// S is the stored type, which is usually the same as T, with the exception of strings
template<typename T>
class HashIndex final : public OnDiskHashIndex {
public:
    HashIndex(MemoryManager& memoryManager, OverflowFileHandle* overflowFileHandle,
        DiskArrayCollection& diskArrays, uint64_t indexPos, ShadowFile* shadowFile,
        const HashIndexHeader& indexHeaderForReadTrx, HashIndexHeader& indexHeaderForWriteTrx);

    ~HashIndex() override;

public:
    using OnDiskSlotType = Slot<T>;
    static constexpr auto PERSISTENT_SLOT_CAPACITY = getSlotCapacity<T>();

    static_assert(DiskArray<OnDiskSlotType>::getAlignedElementSize() <=
                  common::HashIndexConstants::SLOT_CAPACITY_BYTES);
    static_assert(DiskArray<OnDiskSlotType>::getAlignedElementSize() >
                  common::HashIndexConstants::SLOT_CAPACITY_BYTES / 2);

    using Key =
        typename std::conditional<std::same_as<T, common::ku_string_t>, std::string_view, T>::type;
    // For read transactions, local storage is skipped, lookups are performed on the persistent
    // storage. For write transactions, lookups are performed in the local storage first, then in
    // the persistent storage if necessary. In details, there are three cases for the local storage
    // lookup:
    // - the key is found in the local storage, directly return true;
    // - the key has been marked as deleted in the local storage, return false;
    // - the key is neither deleted nor found in the local storage, lookup in the persistent
    // storage.
    bool lookupInternal(const transaction::Transaction* transaction, Key key,
        common::offset_t& result, visible_func isVisible) {
        auto localLookupState = localStorage->lookup(key, result, isVisible);
        if (localLookupState == HashIndexLocalLookupState::KEY_DELETED) {
            return false;
        }
        if (localLookupState == HashIndexLocalLookupState::KEY_FOUND) {
            return true;
        }
        KU_ASSERT(localLookupState == HashIndexLocalLookupState::KEY_NOT_EXIST);
        return lookupInPersistentIndex(transaction, key, result, isVisible);
    }

    // For deletions, we don't check if the deleted keys exist or not. Thus, we don't need to check
    // in the persistent storage and directly delete keys in the local storage.
    void deleteInternal(Key key) const { localStorage->deleteKey(key); }
    // Discards from local storage, but will not insert a deletion (used for rollbacks)
    bool discardLocal(Key key) const { return localStorage->discard(key); }

    // For insertions, we first check in the local storage. There are three cases:
    // - the key is found in the local storage, return false;
    // - the key is marked as deleted in the local storage, insert the key to the local storage;
    // - the key doesn't exist in the local storage, check if the key exists in the persistent
    // index, if
    //   so, return false, else insert the key to the local storage.
    using InsertType = InMemHashIndex<T>::OwnedType;
    bool insertInternal(const transaction::Transaction* transaction, InsertType&& key,
        common::offset_t value, visible_func isVisible) {
        common::offset_t tmpResult = 0;
        auto localLookupState = localStorage->lookup(key, tmpResult, isVisible);
        if (localLookupState == HashIndexLocalLookupState::KEY_FOUND) {
            return false;
        }
        if (localLookupState != HashIndexLocalLookupState::KEY_DELETED) {
            if (lookupInPersistentIndex(transaction, key, tmpResult, isVisible)) {
                return false;
            }
        }
        return localStorage->insert(std::move(key), value, isVisible);
    }

    using BufferKeyType =
        typename std::conditional<std::same_as<T, common::ku_string_t>, std::string, T>::type;
    // Appends the buffer to the index. Returns the number of values successfully inserted
    // Note that this function does not acquire locks internally, as the caller is expected to hold
    // the lock already.
    size_t appendNoLock(const transaction::Transaction* transaction,
        IndexBuffer<BufferKeyType>& buffer, uint64_t bufferOffset, visible_func isVisible) {
        // Check if values already exist in persistent storage
        if (indexHeaderForWriteTrx.numEntries > 0) {
            localStorage->reserveSpaceForAppendNoLock(buffer.size() - bufferOffset);
            size_t numValuesInserted = 0;
            common::offset_t result = 0;
            for (size_t i = bufferOffset; i < buffer.size(); i++) {
                auto& [key, value] = buffer[i];
                if (lookupInPersistentIndex(transaction, key, result, isVisible)) {
                    return i - bufferOffset;
                } else {
                    numValuesInserted +=
                        localStorage->appendNoLock(std::move(key), value, isVisible);
                }
            }
            return numValuesInserted;
        } else {
            return localStorage->appendNoLock(buffer, bufferOffset, isVisible);
        }
    }

    bool tryLock() override { return localStorage->tryLock(); }
    std::unique_lock<std::shared_mutex> adoptLock() override { return localStorage->adoptLock(); }

    bool checkpoint(PageAllocator& pageAllocator) override;
    bool checkpointInMemory() override;
    bool rollbackInMemory() override;
    void rollbackCheckpoint() override;
    void reclaimStorage(PageAllocator& pageAllocator) override;

private:
    bool lookupInPersistentIndex(const transaction::Transaction* transaction, Key key,
        common::offset_t& result, visible_func isVisible) {
        auto& header = transaction->getType() == transaction::TransactionType::CHECKPOINT ?
                           this->indexHeaderForWriteTrx :
                           this->indexHeaderForReadTrx;
        // There may not be any primary key slots if we try to lookup on an empty index
        if (header.numEntries == 0) {
            return false;
        }
        auto hashValue = HashIndexUtils::hash(key);
        auto fingerprint = HashIndexUtils::getFingerprintForHash(hashValue);
        auto iter = getSlotIterator(HashIndexUtils::getPrimarySlotIdForHash(header, hashValue),
            transaction);
        do {
            auto entryPos =
                findMatchedEntryInSlot(transaction, iter.slot, key, fingerprint, isVisible);
            if (entryPos != SlotHeader::INVALID_ENTRY_POS) {
                result = iter.slot.entries[entryPos].value;
                return true;
            }
        } while (nextChainedSlot(transaction, iter));
        return false;
    }
    void deleteFromPersistentIndex(const transaction::Transaction* transaction, Key key,
        visible_func isVisible);

    entry_pos_t findMatchedEntryInSlot(const transaction::Transaction* transaction,
        const OnDiskSlotType& slot, Key key, uint8_t fingerprint,
        const visible_func& isVisible) const {
        for (auto entryPos = 0u; entryPos < PERSISTENT_SLOT_CAPACITY; entryPos++) {
            if (slot.header.isEntryValid(entryPos) &&
                slot.header.fingerprints[entryPos] == fingerprint &&
                equals(transaction, key, slot.entries[entryPos].key) &&
                isVisible(slot.entries[entryPos].value)) {
                return entryPos;
            }
        }
        return SlotHeader::INVALID_ENTRY_POS;
    }

    inline void updateSlot(const transaction::Transaction* transaction, const SlotInfo& slotInfo,
        const OnDiskSlotType& slot) {
        slotInfo.slotType == SlotType::PRIMARY ?
            pSlots->update(transaction, slotInfo.slotId, slot) :
            oSlots->update(transaction, slotInfo.slotId, slot);
    }

    inline OnDiskSlotType getSlot(const transaction::Transaction* transaction,
        const SlotInfo& slotInfo) const {
        return slotInfo.slotType == SlotType::PRIMARY ? pSlots->get(slotInfo.slotId, transaction) :
                                                        oSlots->get(slotInfo.slotId, transaction);
    }

    void splitSlots(PageAllocator& pageAllocator, const transaction::Transaction* transaction,
        HashIndexHeader& header, slot_id_t numSlotsToSplit);

    // Resizes the local storage to support the given number of new entries
    void bulkReserve(uint64_t newEntries) override;
    // Resizes the on-disk index to support the given number of new entries
    void reserve(PageAllocator& pageAllocator, const transaction::Transaction* transaction,
        uint64_t newEntries);

    struct HashIndexEntryView {
        slot_id_t diskSlotId;
        uint8_t fingerprint;
        const SlotEntry<typename InMemHashIndex<T>::OwnedType>* entry;
    };

    void sortEntries(const transaction::Transaction* transaction,
        const InMemHashIndex<T>& insertLocalStorage,
        typename InMemHashIndex<T>::SlotIterator& slotToMerge,
        std::vector<HashIndexEntryView>& entries);
    void mergeBulkInserts(PageAllocator& pageAllocator, const transaction::Transaction* transaction,
        const InMemHashIndex<T>& insertLocalStorage);
    // Returns the number of elements merged which matched the given slot id
    size_t mergeSlot(PageAllocator& pageAllocator, const transaction::Transaction* transaction,
        const std::vector<HashIndexEntryView>& slotToMerge,
        typename DiskArray<OnDiskSlotType>::WriteIterator& diskSlotIterator,
        typename DiskArray<OnDiskSlotType>::WriteIterator& diskOverflowSlotIterator,
        slot_id_t diskSlotId);

    inline bool equals(const transaction::Transaction* /*transaction*/, Key keyToLookup,
        const T& keyInEntry) const {
        return keyToLookup == keyInEntry;
    }

    inline common::hash_t hashStored(const transaction::Transaction* /*transaction*/,
        const T& key) const {
        return HashIndexUtils::hash(key);
    }

    inline common::hash_t hashStored(const transaction::Transaction* /*transaction*/,
        std::string_view key) const {
        return HashIndexUtils::hash(key);
    }

    struct SlotIterator {
        SlotInfo slotInfo;
        OnDiskSlotType slot;
    };

    SlotIterator getSlotIterator(slot_id_t slotId, const transaction::Transaction* transaction) {
        return SlotIterator{SlotInfo{slotId, SlotType::PRIMARY},
            getSlot(transaction, SlotInfo{slotId, SlotType::PRIMARY})};
    }

    bool nextChainedSlot(const transaction::Transaction* transaction, SlotIterator& iter) const {
        KU_ASSERT(iter.slotInfo.slotType == SlotType::PRIMARY ||
                  iter.slotInfo.slotId != iter.slot.header.nextOvfSlotId);
        if (iter.slot.header.nextOvfSlotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
            iter.slotInfo.slotId = iter.slot.header.nextOvfSlotId;
            iter.slotInfo.slotType = SlotType::OVF;
            iter.slot = getSlot(transaction, iter.slotInfo);
            return true;
        }
        return false;
    }

    std::vector<std::pair<SlotInfo, OnDiskSlotType>> getChainedSlots(
        const transaction::Transaction* transaction, slot_id_t pSlotId);

private:
    ShadowFile* shadowFile;
    uint64_t headerPageIdx;
    std::unique_ptr<DiskArray<OnDiskSlotType>> pSlots;
    std::unique_ptr<DiskArray<OnDiskSlotType>> oSlots;
    OverflowFileHandle* overflowFileHandle;
    std::unique_ptr<HashIndexLocalStorage<T>> localStorage;
    const HashIndexHeader& indexHeaderForReadTrx;
    HashIndexHeader& indexHeaderForWriteTrx;
    MemoryManager& memoryManager;
};

template<>
common::hash_t HashIndex<common::ku_string_t>::hashStored(
    const transaction::Transaction* transaction, const common::ku_string_t& key) const;

template<>
bool HashIndex<common::ku_string_t>::equals(const transaction::Transaction* transaction,
    std::string_view keyToLookup, const common::ku_string_t& keyInEntry) const;

struct PrimaryKeyIndexStorageInfo final : IndexStorageInfo {
    common::page_idx_t firstHeaderPage;
    common::page_idx_t overflowHeaderPage;

    PrimaryKeyIndexStorageInfo()
        : firstHeaderPage{common::INVALID_PAGE_IDX}, overflowHeaderPage{common::INVALID_PAGE_IDX} {}
    PrimaryKeyIndexStorageInfo(common::page_idx_t firstHeaderPage,
        common::page_idx_t overflowHeaderPage)
        : firstHeaderPage{firstHeaderPage}, overflowHeaderPage{overflowHeaderPage} {}

    DELETE_COPY_DEFAULT_MOVE(PrimaryKeyIndexStorageInfo);

    std::shared_ptr<common::BufferWriter> serialize() const override {
        auto bufferWriter = std::make_shared<common::BufferWriter>();
        auto serializer = common::Serializer(bufferWriter);
        serializer.write<common::page_idx_t>(firstHeaderPage);
        serializer.write<common::page_idx_t>(overflowHeaderPage);
        return bufferWriter;
    }

    static std::unique_ptr<IndexStorageInfo> deserialize(
        std::unique_ptr<common::BufferReader> reader);
};

class PrimaryKeyIndex final : public Index {
public:
    static constexpr const char* DEFAULT_NAME = "_PK";

    struct InsertState final : Index::InsertState {
        visible_func isVisible; // Function to check visibility of the inserted key

        explicit InsertState(visible_func isVisible_) : isVisible{std::move(isVisible_)} {}
    };

    // Construct an existing index
    PrimaryKeyIndex(IndexInfo indexInfo, std::unique_ptr<IndexStorageInfo> storageInfo,
        bool inMemMode, MemoryManager& memoryManager, PageAllocator& pageAllocator,
        ShadowFile* shadowFile);
    ~PrimaryKeyIndex() override;

    static std::unique_ptr<PrimaryKeyIndex> createNewIndex(IndexInfo indexInfo, bool inMemMode,
        MemoryManager& memoryManager, PageAllocator& pageAllocator, ShadowFile* shadowFile);

    template<typename T>
    inline HashIndex<HashIndexType<T>>* getTypedHashIndex(T key) {
        return common::ku_dynamic_cast<HashIndex<HashIndexType<T>>*>(
            hashIndices[HashIndexUtils::getHashIndexPosition(key)].get());
    }
    template<common::IndexHashable T>
    inline HashIndex<T>* getTypedHashIndexByPos(uint64_t indexPos) {
        return common::ku_dynamic_cast<HashIndex<HashIndexType<T>>*>(hashIndices[indexPos].get());
    }

    bool tryLockTypedIndex(uint64_t indexPos) { return hashIndices[indexPos]->tryLock(); }
    std::unique_lock<std::shared_mutex> adoptLockOfTypedIndex(uint64_t indexPos) {
        return hashIndices[indexPos]->adoptLock();
    }

    bool lookup(const transaction::Transaction* trx, common::ku_string_t key,
        common::offset_t& result, visible_func isVisible) {
        return lookup(trx, key.getAsStringView(), result, isVisible);
    }
    template<common::IndexHashable T>
    inline bool lookup(const transaction::Transaction* trx, T key, common::offset_t& result,
        visible_func isVisible) {
        KU_ASSERT(indexInfo.keyDataTypes[0] == common::TypeUtils::getPhysicalTypeIDForType<T>());
        return getTypedHashIndex(key)->lookupInternal(trx, key, result, isVisible);
    }

    bool lookup(const transaction::Transaction* trx, common::ValueVector* keyVector,
        uint64_t vectorPos, common::offset_t& result, visible_func isVisible);

    std::unique_ptr<Index::InsertState> initInsertState(main::ClientContext*,
        visible_func isVisible) override {
        return std::make_unique<InsertState>(isVisible);
    }
    void insert(transaction::Transaction*, const common::ValueVector&,
        const std::vector<common::ValueVector*>&, Index::InsertState&) override {
        // DO NOTHING.
        // For hash index, we don't need to do anything here because the insertions are handled when
        // the transaction commits.
    }
    bool insert(const transaction::Transaction* transaction, common::ku_string_t key,
        common::offset_t value, visible_func isVisible) {
        return insert(transaction, key.getAsString(), value, isVisible);
    }
    template<common::IndexHashable T>
    inline bool insert(const transaction::Transaction* transaction, T key, common::offset_t value,
        visible_func isVisible) {
        KU_ASSERT(indexInfo.keyDataTypes[0] == common::TypeUtils::getPhysicalTypeIDForType<T>());
        return getTypedHashIndex(key)->insertInternal(transaction, std::move(key), value,
            isVisible);
    }
    bool insert(const transaction::Transaction* transaction, const common::ValueVector* keyVector,
        uint64_t vectorPos, common::offset_t value, visible_func isVisible);
    bool needCommitInsert() const override { return true; }
    void commitInsert(transaction::Transaction* transaction,
        const common::ValueVector& nodeIDVector,
        const std::vector<common::ValueVector*>& indexVectors,
        Index::InsertState& insertState) override;

    // Appends the buffer to the index. Returns the number of values successfully inserted.
    // If a key fails to insert, it immediately returns without inserting any more values,
    // and the returned value is also the index of the key which failed to insert.
    template<common::IndexHashable T>
    size_t appendWithIndexPosNoLock(const transaction::Transaction* transaction,
        IndexBuffer<T>& buffer, uint64_t bufferOffset, uint64_t indexPos, visible_func isVisible) {
        KU_ASSERT(indexInfo.keyDataTypes[0] == common::TypeUtils::getPhysicalTypeIDForType<T>());
        KU_ASSERT(std::all_of(buffer.begin(), buffer.end(), [&](auto& elem) {
            return HashIndexUtils::getHashIndexPosition(elem.first) == indexPos;
        }));
        return getTypedHashIndexByPos<HashIndexType<T>>(indexPos)->appendNoLock(transaction, buffer,
            bufferOffset, isVisible);
    }

    void bulkReserve(uint64_t numValuesToAppend) {
        uint32_t eachSize = numValuesToAppend / NUM_HASH_INDEXES + 1;
        for (auto i = 0u; i < NUM_HASH_INDEXES; i++) {
            hashIndices[i]->bulkReserve(eachSize);
        }
    }

    void delete_(common::ku_string_t key) { return delete_(key.getAsStringView()); }
    std::unique_ptr<DeleteState> initDeleteState(const transaction::Transaction* /*transaction*/,
        MemoryManager* /*mm*/, visible_func /*isVisible*/) override {
        return std::make_unique<DeleteState>();
    }
    void delete_(transaction::Transaction* /*transaction*/,
        const common::ValueVector& /*nodeIDVector*/, DeleteState& /*deleteState*/) override {
        // DO NOTHING.
    }
    template<common::IndexHashable T>
    inline void delete_(T key) {
        KU_ASSERT(indexInfo.keyDataTypes[0] == common::TypeUtils::getPhysicalTypeIDForType<T>());
        return getTypedHashIndex(key)->deleteInternal(key);
    }

    bool discardLocal(common::ku_string_t key) { return discardLocal(key.getAsStringView()); }
    template<common::IndexHashable T>
    inline bool discardLocal(T key) {
        KU_ASSERT(indexInfo.keyDataTypes[0] == common::TypeUtils::getPhysicalTypeIDForType<T>());
        return getTypedHashIndex(key)->discardLocal(key);
    }

    void delete_(common::ValueVector* keyVector);

    void checkpointInMemory() override;
    void checkpoint(main::ClientContext*, storage::PageAllocator& pageAllocator) override;
    OverflowFile* getOverflowFile() const { return overflowFile.get(); }

    void rollbackCheckpoint() override;

    common::PhysicalTypeID keyTypeID() const {
        KU_ASSERT(indexInfo.keyDataTypes.size() == 1);
        return indexInfo.keyDataTypes[0];
    }
    void reclaimStorage(PageAllocator& pageAllocator) const;

    static LBUG_API std::unique_ptr<Index> load(main::ClientContext* context,
        StorageManager* storageManager, IndexInfo indexInfo, std::span<uint8_t> storageInfoBuffer);

    static IndexType getIndexType() {
        static const IndexType HASH_INDEX_TYPE{"HASH", IndexConstraintType::PRIMARY,
            IndexDefinitionType::BUILTIN, load};
        return HASH_INDEX_TYPE;
    }

private:
    void writeHeaders(PageAllocator& pageAllocator) const;

    void initOverflowAndSubIndices(bool inMemMode, MemoryManager& mm, PageAllocator& pageAllocator,
        PrimaryKeyIndexStorageInfo& storageInfo);

    common::page_idx_t getFirstHeaderPage() const;

    common::page_idx_t getDiskArrayFirstHeaderPage() const;

private:
    std::unique_ptr<OverflowFile> overflowFile;
    std::vector<std::unique_ptr<OnDiskHashIndex>> hashIndices;
    std::vector<HashIndexHeader> hashIndexHeadersForReadTrx;
    std::vector<HashIndexHeader> hashIndexHeadersForWriteTrx;
    ShadowFile& shadowFile;
    // Stores both primary and overflow slots
    std::unique_ptr<DiskArrayCollection> hashIndexDiskArrays;
};

} // namespace storage
} // namespace lbug
