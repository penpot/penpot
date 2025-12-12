#include "storage/index/hash_index.h"

#include <bitset>

#include "common/assert.h"
#include "common/exception/message.h"
#include "common/serializer/deserializer.h"
#include "common/types/int128_t.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "common/types/uint128_t.h"
#include "main/client_context.h"
#include "storage/disk_array.h"
#include "storage/disk_array_collection.h"
#include "storage/file_handle.h"
#include "storage/index/hash_index_header.h"
#include "storage/index/hash_index_slot.h"
#include "storage/index/hash_index_utils.h"
#include "storage/index/in_mem_hash_index.h"
#include "storage/local_storage/local_hash_index.h"
#include "storage/overflow_file.h"
#include "storage/shadow_utils.h"
#include "storage/storage_manager.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

template<typename T>
HashIndex<T>::HashIndex(MemoryManager& memoryManager, OverflowFileHandle* overflowFileHandle,
    DiskArrayCollection& diskArrays, uint64_t indexPos, ShadowFile* shadowFile,
    const HashIndexHeader& indexHeaderForReadTrx, HashIndexHeader& indexHeaderForWriteTrx)
    : shadowFile{shadowFile}, headerPageIdx{0}, overflowFileHandle{overflowFileHandle},
      localStorage{std::make_unique<HashIndexLocalStorage<T>>(memoryManager, overflowFileHandle)},
      indexHeaderForReadTrx{indexHeaderForReadTrx}, indexHeaderForWriteTrx{indexHeaderForWriteTrx},
      memoryManager{memoryManager} {
    pSlots = diskArrays.getDiskArray<OnDiskSlotType>(indexPos);
    oSlots = diskArrays.getDiskArray<OnDiskSlotType>(NUM_HASH_INDEXES + indexPos);
}

template<typename T>
void HashIndex<T>::deleteFromPersistentIndex(const Transaction* transaction, Key key,
    visible_func isVisible) {
    auto& header = this->indexHeaderForWriteTrx;
    if (header.numEntries == 0) {
        return;
    }
    auto hashValue = HashIndexUtils::hash(key);
    auto fingerprint = HashIndexUtils::getFingerprintForHash(hashValue);
    auto iter =
        getSlotIterator(HashIndexUtils::getPrimarySlotIdForHash(header, hashValue), transaction);
    do {
        auto entryPos = findMatchedEntryInSlot(transaction, iter.slot, key, fingerprint, isVisible);
        if (entryPos != SlotHeader::INVALID_ENTRY_POS) {
            iter.slot.header.setEntryInvalid(entryPos);
            updateSlot(transaction, iter.slotInfo, iter.slot);
            header.numEntries--;
        }
    } while (nextChainedSlot(transaction, iter));
}

template<>
inline hash_t HashIndex<ku_string_t>::hashStored(const Transaction* transaction,
    const ku_string_t& key) const {
    hash_t hash = 0;
    const auto str = overflowFileHandle->readString(transaction->getType(), key);
    function::Hash::operation(str, hash);
    return hash;
}

template<typename T>
bool HashIndex<T>::checkpoint(PageAllocator& pageAllocator) {
    if (localStorage->hasUpdates()) {
        auto transaction = &DUMMY_CHECKPOINT_TRANSACTION;
        auto netInserts = localStorage->getNetInserts();
        if (netInserts > 0) {
            reserve(pageAllocator, transaction, netInserts);
        }
        localStorage->applyLocalChanges(
            [&](Key) {
                // TODO(Guodong/Ben): FIX-ME. We should vacuum the index during checkpoint.
                // DO NOTHING.
            },
            [&](const auto& insertions) {
                mergeBulkInserts(pageAllocator, transaction, insertions);
            });
        pSlots->checkpoint();
        oSlots->checkpoint();
        return true;
    }
    pSlots->checkpoint();
    oSlots->checkpoint();
    return false;
}

template<typename T>
bool HashIndex<T>::checkpointInMemory() {
    if (!localStorage->hasUpdates()) {
        return false;
    }
    pSlots->checkpointInMemoryIfNecessary();
    oSlots->checkpointInMemoryIfNecessary();
    localStorage->clear();
    if constexpr (std::same_as<ku_string_t, T>) {
        overflowFileHandle->checkpointInMemory();
    }
    return true;
}

template<typename T>
bool HashIndex<T>::rollbackInMemory() {
    if (!localStorage->hasUpdates()) {
        return false;
    }
    pSlots->rollbackInMemoryIfNecessary();
    oSlots->rollbackInMemoryIfNecessary();
    localStorage->clear();
    return true;
}

template<typename T>
void HashIndex<T>::rollbackCheckpoint() {
    pSlots->rollbackInMemoryIfNecessary();
    oSlots->rollbackInMemoryIfNecessary();
}

template<typename T>
void HashIndex<T>::reclaimStorage(PageAllocator& pageAllocator) {
    pSlots->reclaimStorage(pageAllocator);
    oSlots->reclaimStorage(pageAllocator);
}

template<typename T>
void HashIndex<T>::splitSlots(PageAllocator& pageAllocator, const Transaction* transaction,
    HashIndexHeader& header, slot_id_t numSlotsToSplit) {
    auto originalSlotIterator = pSlots->iter_mut();
    auto newSlotIterator = pSlots->iter_mut();
    auto overflowSlotIterator = oSlots->iter_mut();
    // The overflow slot iterators will hang if they access the same page
    // So instead buffer new overflow slots here and append them at the end
    std::vector<OnDiskSlotType> newOverflowSlots;

    auto getNextOvfSlot = [&](slot_id_t nextOvfSlotId) {
        if (nextOvfSlotId >= oSlots->getNumElements()) {
            return &newOverflowSlots[nextOvfSlotId - oSlots->getNumElements()];
        } else {
            return &*overflowSlotIterator.seek(nextOvfSlotId);
        }
    };

    for (slot_id_t i = 0; i < numSlotsToSplit; i++) {
        auto* newSlot = &*newSlotIterator.pushBack(pageAllocator, transaction, OnDiskSlotType());
        entry_pos_t newEntryPos = 0;
        OnDiskSlotType* originalSlot = &*originalSlotIterator.seek(header.nextSplitSlotId);
        do {
            for (entry_pos_t originalEntryPos = 0; originalEntryPos < PERSISTENT_SLOT_CAPACITY;
                 originalEntryPos++) {
                if (!originalSlot->header.isEntryValid(originalEntryPos)) {
                    continue; // Skip invalid entries.
                }
                if (newEntryPos >= PERSISTENT_SLOT_CAPACITY) {
                    newSlot->header.nextOvfSlotId =
                        newOverflowSlots.size() + oSlots->getNumElements();
                    newOverflowSlots.emplace_back();
                    newSlot = &newOverflowSlots.back();
                    newEntryPos = 0;
                }
                // Copy entry from old slot to new slot
                const auto& key = originalSlot->entries[originalEntryPos].key;
                const hash_t hash = this->hashStored(transaction, key);
                const auto newSlotId = hash & header.higherLevelHashMask;
                if (newSlotId != header.nextSplitSlotId) {
                    KU_ASSERT(newSlotId == newSlotIterator.idx());
                    newSlot->entries[newEntryPos] = originalSlot->entries[originalEntryPos];
                    newSlot->header.setEntryValid(newEntryPos,
                        originalSlot->header.fingerprints[originalEntryPos]);
                    originalSlot->header.setEntryInvalid(originalEntryPos);
                    newEntryPos++;
                }
            }
        } while (originalSlot->header.nextOvfSlotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID &&
                 (originalSlot = getNextOvfSlot(originalSlot->header.nextOvfSlotId)));
        header.incrementNextSplitSlotId();
    }
    for (auto&& slot : newOverflowSlots) {
        overflowSlotIterator.pushBack(pageAllocator, transaction, std::move(slot));
    }
}

template<typename T>
std::vector<std::pair<SlotInfo, typename HashIndex<T>::OnDiskSlotType>>
HashIndex<T>::getChainedSlots(const Transaction* transaction, slot_id_t pSlotId) {
    std::vector<std::pair<SlotInfo, OnDiskSlotType>> slots;
    SlotInfo slotInfo{pSlotId, SlotType::PRIMARY};
    while (slotInfo.slotType == SlotType::PRIMARY ||
           slotInfo.slotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
        auto slot = getSlot(transaction, slotInfo);
        slots.emplace_back(slotInfo, slot);
        slotInfo.slotId = slot.header.nextOvfSlotId;
        slotInfo.slotType = SlotType::OVF;
    }
    return slots;
}

template<typename T>
void HashIndex<T>::reserve(PageAllocator& pageAllocator, const Transaction* transaction,
    uint64_t newEntries) {
    slot_id_t numRequiredEntries =
        HashIndexUtils::getNumRequiredEntries(this->indexHeaderForWriteTrx.numEntries + newEntries);
    // Can be no fewer slots than the current level requires
    auto numRequiredSlots =
        std::max((numRequiredEntries + PERSISTENT_SLOT_CAPACITY - 1) / PERSISTENT_SLOT_CAPACITY,
            static_cast<slot_id_t>(1ul << this->indexHeaderForWriteTrx.currentLevel));
    // Always start with at least one page worth of slots.
    // This guarantees that when splitting the source and destination slot are never on the same
    // page, which allows safe use of multiple disk array iterators.
    numRequiredSlots = std::max(numRequiredSlots, LBUG_PAGE_SIZE / pSlots->getAlignedElementSize());
    // If there are no entries, we can just re-size the number of primary slots and re-calculate the
    // levels
    if (this->indexHeaderForWriteTrx.numEntries == 0) {
        pSlots->resize(pageAllocator, transaction, numRequiredSlots);

        auto numSlotsOfCurrentLevel = 1u << this->indexHeaderForWriteTrx.currentLevel;
        while ((numSlotsOfCurrentLevel << 1) <= numRequiredSlots) {
            this->indexHeaderForWriteTrx.incrementLevel();
            numSlotsOfCurrentLevel <<= 1;
        }
        if (numRequiredSlots >= numSlotsOfCurrentLevel) {
            this->indexHeaderForWriteTrx.nextSplitSlotId =
                numRequiredSlots - numSlotsOfCurrentLevel;
        }
    } else {
        splitSlots(pageAllocator, transaction, this->indexHeaderForWriteTrx,
            numRequiredSlots - pSlots->getNumElements(transaction->getType()));
    }
}

template<typename T>
void HashIndex<T>::sortEntries(const Transaction* transaction,
    const InMemHashIndex<T>& insertLocalStorage,
    typename InMemHashIndex<T>::SlotIterator& slotToMerge,
    std::vector<HashIndexEntryView>& entries) {
    do {
        auto numEntries = slotToMerge.slot->header.numEntries();
        for (auto entryPos = 0u; entryPos < numEntries; entryPos++) {
            const auto* entry = &slotToMerge.slot->entries[entryPos];
            const auto hash = hashStored(transaction, entry->key);
            const auto primarySlot =
                HashIndexUtils::getPrimarySlotIdForHash(indexHeaderForWriteTrx, hash);
            entries.push_back(HashIndexEntryView{primarySlot,
                slotToMerge.slot->header.fingerprints[entryPos], entry});
        }
    } while (insertLocalStorage.nextChainedSlot(slotToMerge));
    std::sort(entries.begin(), entries.end(), [&](auto entry1, auto entry2) -> bool {
        // Sort based on the entry's disk slot ID so that the first slot is at the end.
        // Sorting is done reversed so that we can process from the back of the list,
        // using the size to track the remaining entries
        return entry1.diskSlotId > entry2.diskSlotId;
    });
}

template<typename T>
void HashIndex<T>::mergeBulkInserts(PageAllocator& pageAllocator, const Transaction* transaction,
    const InMemHashIndex<T>& insertLocalStorage) {
    // TODO: Ideally we can split slots at the same time that we insert new ones
    // Compute the new number of primary slots, and iterate over each slot, determining if it
    // needs to be split (and how many times, which is complicated) and insert/rehash each element
    // one by one. Rehashed entries should be copied into a new slot in-memory, and then that new
    // slot (with the entries from the respective slot in the local storage) should be processed
    // immediately to avoid increasing memory usage (caching one page of slots at a time since split
    // slots usually get rehashed to a new page).
    //
    // On the other hand, two passes may not be significantly slower than one
    // TODO: one pass would also reduce locking when frames are unpinned,
    // which is useful if this can be parallelized
    reserve(pageAllocator, transaction, insertLocalStorage.size());
    // RUNTIME_CHECK(auto originalNumEntries = this->indexHeaderForWriteTrx.numEntries);

    // Storing as many slots in-memory as on-disk shouldn't be necessary (for one, it makes memory
    // usage an issue as we may need significantly more memory to store the slots that we would
    // otherwise). Instead, when merging here, we can re-hash and split each in-memory slot (into
    // temporary vector buffers instead of slots for improved performance) and then merge each of
    // those one at a time into the disk slots. That will keep the low memory requirements and still
    // let us update each on-disk slot one at a time.

    auto diskSlotIterator = pSlots->iter_mut();
    // TODO: Use a separate random access iterator and one that's sequential for adding new overflow
    // slots All new slots will be sequential and benefit from caching, but for existing randomly
    // accessed slots we just benefit from the interface. However, the two iterators would not be
    // able to pin the same page simultaneously
    // Alternatively, cache new slots in memory and pushBack them at the end like in splitSlots
    auto diskOverflowSlotIterator = oSlots->iter_mut();

    // Store sorted slot positions. Re-use to avoid re-allocating memory
    // TODO: Unify implementations to make sure this matches the size used by the disk array
    constexpr size_t NUM_SLOTS_PER_PAGE =
        LBUG_PAGE_SIZE / DiskArray<OnDiskSlotType>::getAlignedElementSize();
    std::array<std::vector<HashIndexEntryView>, NUM_SLOTS_PER_PAGE> partitionedEntries;
    // Sort entries for a page of slots at a time, then move vertically and process all entries
    // which map to a given page on disk, then horizontally to the next page in the set. These pages
    // may not be consecutive, but we reduce the memory overhead for storing the information about
    // the sorted data and still just process each page once.
    for (uint64_t localSlotId = 0; localSlotId < insertLocalStorage.numPrimarySlots();
         localSlotId += NUM_SLOTS_PER_PAGE) {
        for (size_t i = 0;
             i < NUM_SLOTS_PER_PAGE && localSlotId + i < insertLocalStorage.numPrimarySlots();
             i++) {
            auto localSlot =
                typename InMemHashIndex<T>::SlotIterator(localSlotId + i, &insertLocalStorage);
            partitionedEntries[i].clear();
            // Results are sorted in reverse, so we can process the end first and pop_back to remove
            // them from the vector
            sortEntries(transaction, insertLocalStorage, localSlot, partitionedEntries[i]);
        }
        // Repeat until there are no unprocessed partitions in partitionedEntries
        // This will run at most NUM_SLOTS_PER_PAGE times the number of entries
        std::bitset<NUM_SLOTS_PER_PAGE> done;
        while (!done.all()) {
            std::optional<page_idx_t> diskSlotPage;
            for (size_t i = 0; i < NUM_SLOTS_PER_PAGE; i++) {
                if (!done[i] && !partitionedEntries[i].empty()) {
                    auto diskSlotId = partitionedEntries[i].back().diskSlotId;
                    if (!diskSlotPage) {
                        diskSlotPage = diskSlotId / NUM_SLOTS_PER_PAGE;
                    }
                    if (diskSlotId / NUM_SLOTS_PER_PAGE == diskSlotPage) {
                        auto merged = mergeSlot(pageAllocator, transaction, partitionedEntries[i],
                            diskSlotIterator, diskOverflowSlotIterator, diskSlotId);
                        KU_ASSERT(merged <= partitionedEntries[i].size());
                        partitionedEntries[i].resize(partitionedEntries[i].size() - merged);
                        if (partitionedEntries[i].empty()) {
                            done[i] = true;
                        }
                    }
                } else {
                    done[i] = true;
                }
            }
        }
    }
    // TODO(Guodong): Fix this assertion statement which doesn't count the entries in
    // deleteLocalStorage.
    //     KU_ASSERT(originalNumEntries + insertLocalStorage.getIndexHeader().numEntries ==
    //               indexHeaderForWriteTrx.numEntries);
}

template<typename T>
size_t HashIndex<T>::mergeSlot(PageAllocator& pageAllocator, const Transaction* transaction,
    const std::vector<HashIndexEntryView>& slotToMerge,
    typename DiskArray<OnDiskSlotType>::WriteIterator& diskSlotIterator,
    typename DiskArray<OnDiskSlotType>::WriteIterator& diskOverflowSlotIterator,
    slot_id_t diskSlotId) {
    slot_id_t diskEntryPos = 0u;
    // mergeSlot should only be called when there is at least one entry for the given disk slot id
    // in the slot to merge
    OnDiskSlotType* diskSlot = &*diskSlotIterator.seek(diskSlotId);
    KU_ASSERT(diskSlot->header.nextOvfSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID ||
              diskOverflowSlotIterator.size() > diskSlot->header.nextOvfSlotId);
    // Merge slot from local storage to an existing slot.
    size_t merged = 0;
    for (auto it = std::rbegin(slotToMerge); it != std::rend(slotToMerge); ++it) {
        if (it->diskSlotId != diskSlotId) {
            return merged;
        }
        // Find the next empty entry or add a new slot if there are no more entries
        while (diskSlot->header.isEntryValid(diskEntryPos) ||
               diskEntryPos >= PERSISTENT_SLOT_CAPACITY) {
            diskEntryPos++;
            if (diskEntryPos >= PERSISTENT_SLOT_CAPACITY) {
                if (diskSlot->header.nextOvfSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
                    // If there are no more disk slots in this chain, we need to add one
                    diskSlot->header.nextOvfSlotId = diskOverflowSlotIterator.size();
                    // This may invalidate diskSlot
                    diskOverflowSlotIterator.pushBack(pageAllocator, transaction, OnDiskSlotType());
                    KU_ASSERT(
                        diskSlot->header.nextOvfSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID ||
                        diskOverflowSlotIterator.size() > diskSlot->header.nextOvfSlotId);
                } else {
                    diskOverflowSlotIterator.seek(diskSlot->header.nextOvfSlotId);
                    KU_ASSERT(
                        diskSlot->header.nextOvfSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID ||
                        diskOverflowSlotIterator.size() > diskSlot->header.nextOvfSlotId);
                }
                diskSlot = &*diskOverflowSlotIterator;
                // Check to make sure we're not looping
                KU_ASSERT(diskOverflowSlotIterator.idx() != diskSlot->header.nextOvfSlotId);
                diskEntryPos = 0;
            }
        }
        KU_ASSERT(diskEntryPos < PERSISTENT_SLOT_CAPACITY);
        if constexpr (std::is_same_v<T, ku_string_t>) {
            auto* inMemEntry = it->entry;
            auto kuString = overflowFileHandle->writeString(&pageAllocator, inMemEntry->key);
            diskSlot->entries[diskEntryPos] = SlotEntry<T>{kuString, inMemEntry->value};
        } else {
            diskSlot->entries[diskEntryPos] = *it->entry;
        }
        diskSlot->header.setEntryValid(diskEntryPos, it->fingerprint);
        KU_ASSERT([&]() {
            const auto& key = it->entry->key;
            const auto hash = hashStored(transaction, key);
            const auto primarySlot =
                HashIndexUtils::getPrimarySlotIdForHash(indexHeaderForWriteTrx, hash);
            KU_ASSERT(it->fingerprint == HashIndexUtils::getFingerprintForHash(hash));
            KU_ASSERT(primarySlot == diskSlotId);
            return true;
        }());
        indexHeaderForWriteTrx.numEntries++;
        diskEntryPos++;
        merged++;
    }
    return merged;
}

template<typename T>
void HashIndex<T>::bulkReserve(uint64_t newEntries) {
    return localStorage->reserveInserts(newEntries);
}

template<typename T>
HashIndex<T>::~HashIndex() = default;

template<>
bool HashIndex<common::ku_string_t>::equals(const transaction::Transaction* transaction,
    std::string_view keyToLookup, const common::ku_string_t& keyInEntry) const {
    if (!HashIndexUtils::areStringPrefixAndLenEqual(keyToLookup, keyInEntry)) {
        return false;
    }
    if (keyInEntry.len <= common::ku_string_t::PREFIX_LENGTH) {
        // For strings shorter than PREFIX_LENGTH, the result must be true.
        return true;
    } else if (keyInEntry.len <= common::ku_string_t::SHORT_STR_LENGTH) {
        // For short strings, whose lengths are larger than PREFIX_LENGTH, check if their
        // actual values are equal.
        return memcmp(keyToLookup.data(), keyInEntry.prefix, keyInEntry.len) == 0;
    } else {
        // For long strings, compare with overflow data
        return overflowFileHandle->equals(transaction->getType(), keyToLookup, keyInEntry);
    }
}

template class HashIndex<int64_t>;
template class HashIndex<int32_t>;
template class HashIndex<int16_t>;
template class HashIndex<int8_t>;
template class HashIndex<uint64_t>;
template class HashIndex<uint32_t>;
template class HashIndex<uint16_t>;
template class HashIndex<uint8_t>;
template class HashIndex<double>;
template class HashIndex<float>;
template class HashIndex<int128_t>;
template class HashIndex<uint128_t>;
template class HashIndex<ku_string_t>;

std::unique_ptr<IndexStorageInfo> PrimaryKeyIndexStorageInfo::deserialize(
    std::unique_ptr<BufferReader> reader) {
    page_idx_t firstHeaderPage = INVALID_PAGE_IDX;
    page_idx_t overflowHeaderPage = INVALID_PAGE_IDX;
    Deserializer deSer(std::move(reader));
    deSer.deserializeValue(firstHeaderPage);
    deSer.deserializeValue(overflowHeaderPage);
    return std::make_unique<PrimaryKeyIndexStorageInfo>(firstHeaderPage, overflowHeaderPage);
}

std::unique_ptr<PrimaryKeyIndex> PrimaryKeyIndex::createNewIndex(IndexInfo indexInfo,
    bool inMemMode, MemoryManager& memoryManager, PageAllocator& pageAllocator,
    ShadowFile* shadowFile) {
    return std::make_unique<PrimaryKeyIndex>(std::move(indexInfo),
        std::make_unique<PrimaryKeyIndexStorageInfo>(), inMemMode, memoryManager, pageAllocator,
        shadowFile);
}

PrimaryKeyIndex::PrimaryKeyIndex(IndexInfo indexInfo, std::unique_ptr<IndexStorageInfo> storageInfo,
    bool inMemMode, MemoryManager& memoryManager, PageAllocator& pageAllocator,
    ShadowFile* shadowFile)
    : Index{std::move(indexInfo), std::move(storageInfo)}, shadowFile{*shadowFile} {
    auto& hashIndexStorageInfo = this->storageInfo->cast<PrimaryKeyIndexStorageInfo>();
    if (hashIndexStorageInfo.firstHeaderPage == INVALID_PAGE_IDX) {
        KU_ASSERT(hashIndexStorageInfo.overflowHeaderPage == INVALID_PAGE_IDX);
        hashIndexHeadersForReadTrx.resize(NUM_HASH_INDEXES);
        hashIndexHeadersForWriteTrx.resize(NUM_HASH_INDEXES);
        hashIndexDiskArrays = std::make_unique<DiskArrayCollection>(*pageAllocator.getDataFH(),
            *shadowFile, true /*bypassShadowing*/);
        // Each index has a primary slot array and an overflow slot array
        for (size_t i = 0; i < NUM_HASH_INDEXES * 2; i++) {
            hashIndexDiskArrays->addDiskArray();
        }
    } else {
        size_t headerIdx = 0;
        for (size_t headerPageIdx = 0; headerPageIdx < INDEX_HEADER_PAGES; headerPageIdx++) {
            pageAllocator.getDataFH()->optimisticReadPage(
                hashIndexStorageInfo.firstHeaderPage + headerPageIdx, [&](auto* frame) {
                    const auto onDiskHeaders = reinterpret_cast<HashIndexHeaderOnDisk*>(frame);
                    for (size_t i = 0; i < INDEX_HEADERS_PER_PAGE && headerIdx < NUM_HASH_INDEXES;
                         i++) {
                        hashIndexHeadersForReadTrx.emplace_back(onDiskHeaders[i]);
                        headerIdx++;
                    }
                });
        }
        hashIndexHeadersForWriteTrx.assign(hashIndexHeadersForReadTrx.begin(),
            hashIndexHeadersForReadTrx.end());
        KU_ASSERT(headerIdx == NUM_HASH_INDEXES);
        hashIndexDiskArrays = std::make_unique<DiskArrayCollection>(*pageAllocator.getDataFH(),
            *shadowFile,
            hashIndexStorageInfo.firstHeaderPage +
                INDEX_HEADER_PAGES /*firstHeaderPage for the DAC follows the index header pages*/,
            true /*bypassShadowing*/);
    }
    initOverflowAndSubIndices(inMemMode, memoryManager, pageAllocator, hashIndexStorageInfo);
}

void PrimaryKeyIndex::initOverflowAndSubIndices(bool inMemMode, MemoryManager& mm,
    PageAllocator& pageAllocator, PrimaryKeyIndexStorageInfo& storageInfo) {
    KU_ASSERT(indexInfo.keyDataTypes.size() == 1);
    if (indexInfo.keyDataTypes[0] == PhysicalTypeID::STRING) {
        if (inMemMode) {
            overflowFile = std::make_unique<InMemOverflowFile>(mm);
        } else {
            overflowFile = std::make_unique<OverflowFile>(pageAllocator.getDataFH(), mm,
                &shadowFile, storageInfo.overflowHeaderPage);
        }
    }
    hashIndices.reserve(NUM_HASH_INDEXES);
    TypeUtils::visit(
        indexInfo.keyDataTypes[0],
        [&](ku_string_t) {
            for (auto i = 0u; i < NUM_HASH_INDEXES; i++) {
                hashIndices.push_back(std::make_unique<HashIndex<ku_string_t>>(mm,
                    overflowFile->addHandle(), *hashIndexDiskArrays, i, &shadowFile,
                    hashIndexHeadersForReadTrx[i], hashIndexHeadersForWriteTrx[i]));
            }
        },
        [&]<HashablePrimitive T>(T) {
            for (auto i = 0u; i < NUM_HASH_INDEXES; i++) {
                hashIndices.push_back(std::make_unique<HashIndex<T>>(mm, nullptr,
                    *hashIndexDiskArrays, i, &shadowFile, hashIndexHeadersForReadTrx[i],
                    hashIndexHeadersForWriteTrx[i]));
            }
        },
        [&](auto) { KU_UNREACHABLE; });
}

bool PrimaryKeyIndex::lookup(const Transaction* trx, ValueVector* keyVector, uint64_t vectorPos,
    offset_t& result, visible_func isVisible) {
    bool retVal = false;
    KU_ASSERT(indexInfo.keyDataTypes.size() == 1);
    TypeUtils::visit(
        indexInfo.keyDataTypes[0],
        [&]<IndexHashable T>(T) {
            T key = keyVector->getValue<T>(vectorPos);
            retVal = lookup(trx, key, result, isVisible);
        },
        [](auto) { KU_UNREACHABLE; });
    return retVal;
}

void PrimaryKeyIndex::commitInsert(Transaction* transaction, const ValueVector& nodeIDVector,
    const std::vector<ValueVector*>& indexVectors, Index::InsertState& insertState) {
    KU_ASSERT(indexVectors.size() == 1);
    const auto& pkVector = *indexVectors[0];
    const auto& pkInsertState = insertState.cast<InsertState>();
    for (auto i = 0u; i < nodeIDVector.state->getSelSize(); i++) {
        const auto nodeIDPos = nodeIDVector.state->getSelVector()[i];
        const auto offset = nodeIDVector.readNodeOffset(nodeIDPos);
        const auto pkPos = pkVector.state->getSelVector()[i];
        if (pkVector.isNull(pkPos)) {
            throw RuntimeException(ExceptionMessage::nullPKException());
        }
        if (!insert(transaction, &pkVector, pkPos, offset, pkInsertState.isVisible)) {
            throw RuntimeException(
                ExceptionMessage::duplicatePKException(pkVector.getAsValue(pkPos)->toString()));
        }
    }
}

bool PrimaryKeyIndex::insert(const Transaction* transaction, const ValueVector* keyVector,
    uint64_t vectorPos, offset_t value, visible_func isVisible) {
    bool result = false;
    KU_ASSERT(indexInfo.keyDataTypes.size() == 1);
    TypeUtils::visit(
        indexInfo.keyDataTypes[0],
        [&]<IndexHashable T>(T) {
            T key = keyVector->getValue<T>(vectorPos);
            result = insert(transaction, key, value, isVisible);
        },
        [](auto) { KU_UNREACHABLE; });
    return result;
}

void PrimaryKeyIndex::delete_(ValueVector* keyVector) {
    KU_ASSERT(indexInfo.keyDataTypes.size() == 1);
    TypeUtils::visit(
        indexInfo.keyDataTypes[0],
        [&]<IndexHashable T>(T) {
            for (auto i = 0u; i < keyVector->state->getSelVector().getSelSize(); i++) {
                auto pos = keyVector->state->getSelVector()[i];
                if (keyVector->isNull(pos)) {
                    continue;
                }
                auto key = keyVector->getValue<T>(pos);
                delete_(key);
            }
        },
        [](auto) { KU_UNREACHABLE; });
}

void PrimaryKeyIndex::checkpointInMemory() {
    bool indexChanged = false;
    for (auto i = 0u; i < NUM_HASH_INDEXES; i++) {
        if (hashIndices[i]->checkpointInMemory()) {
            indexChanged = true;
        }
    }
    if (indexChanged) {
        for (size_t i = 0; i < NUM_HASH_INDEXES; i++) {
            hashIndexHeadersForReadTrx[i] = hashIndexHeadersForWriteTrx[i];
        }
        hashIndexDiskArrays->checkpointInMemory();
    }
    if (overflowFile) {
        overflowFile->checkpointInMemory();
    }
}

void PrimaryKeyIndex::writeHeaders(PageAllocator& pageAllocator) const {
    size_t headerIdx = 0;
    auto& hashIndexStorageInfo = storageInfo->cast<PrimaryKeyIndexStorageInfo>();
    if (hashIndexStorageInfo.firstHeaderPage == INVALID_PAGE_IDX) {
        const auto allocatedPages = pageAllocator.allocatePageRange(
            NUM_HEADER_PAGES + 1 /*first DiskArrayCollection header page*/);
        hashIndexStorageInfo.firstHeaderPage = allocatedPages.startPageIdx;
    }
    for (size_t headerPageIdx = 0; headerPageIdx < INDEX_HEADER_PAGES; headerPageIdx++) {
        ShadowUtils::updatePage(*pageAllocator.getDataFH(),
            hashIndexStorageInfo.firstHeaderPage + headerPageIdx,
            true /*writing all the data to the page; no need to read original*/, shadowFile,
            [&](auto* frame) {
                const auto onDiskFrame = reinterpret_cast<HashIndexHeaderOnDisk*>(frame);
                for (size_t i = 0; i < INDEX_HEADERS_PER_PAGE && headerIdx < NUM_HASH_INDEXES;
                     i++) {
                    hashIndexHeadersForWriteTrx[headerIdx++].write(onDiskFrame[i]);
                }
            });
    }
    KU_ASSERT(headerIdx == NUM_HASH_INDEXES);
}

void PrimaryKeyIndex::rollbackCheckpoint() {
    for (idx_t i = 0; i < NUM_HASH_INDEXES; ++i) {
        hashIndices[i]->rollbackCheckpoint();
    }
    hashIndexDiskArrays->rollbackCheckpoint();
    hashIndexHeadersForWriteTrx.assign(hashIndexHeadersForReadTrx.begin(),
        hashIndexHeadersForReadTrx.end());
    if (overflowFile) {
        overflowFile->rollbackInMemory();
    }
}

static void updateOverflowHeaderPageIfNeeded(IndexStorageInfo* storageInfo,
    OverflowFile* overflowFile) {
    auto& hashIndexStorageInfo = storageInfo->cast<PrimaryKeyIndexStorageInfo>();
    if (hashIndexStorageInfo.overflowHeaderPage == INVALID_PAGE_IDX) {
        hashIndexStorageInfo.overflowHeaderPage = overflowFile->getHeaderPageIdx();
    }
}

void PrimaryKeyIndex::checkpoint(main::ClientContext*, storage::PageAllocator& pageAllocator) {
    bool indexChanged = false;
    for (auto i = 0u; i < NUM_HASH_INDEXES; i++) {
        if (hashIndices[i]->checkpoint(pageAllocator)) {
            indexChanged = true;
        }
    }
    if (indexChanged) {
        writeHeaders(pageAllocator);
        hashIndexDiskArrays->checkpoint(getDiskArrayFirstHeaderPage(), pageAllocator);
    }
    if (overflowFile) {
        overflowFile->checkpoint(pageAllocator);
        updateOverflowHeaderPageIfNeeded(storageInfo.get(), overflowFile.get());
    }
    // Make sure that changes which bypassed the WAL are written.
    // There is no other mechanism for enforcing that they are flushed
    // and they will be dropped when the file handle is destroyed.
    // TODO: Should eventually be moved into the disk array when the disk array can
    // generally handle bypassing the WAL, but should only be run once per file, not once per
    // disk array
    pageAllocator.getDataFH()->flushAllDirtyPagesInFrames();
    checkpointInMemory();
}

PrimaryKeyIndex::~PrimaryKeyIndex() = default;

std::unique_ptr<Index> PrimaryKeyIndex::load(main::ClientContext* context,
    StorageManager* storageManager, IndexInfo indexInfo, std::span<uint8_t> storageInfoBuffer) {
    auto storageInfoBufferReader =
        std::make_unique<BufferReader>(storageInfoBuffer.data(), storageInfoBuffer.size());
    auto storageInfo = PrimaryKeyIndexStorageInfo::deserialize(std::move(storageInfoBufferReader));
    return std::make_unique<PrimaryKeyIndex>(indexInfo, std::move(storageInfo),
        storageManager->isInMemory(), *MemoryManager::Get(*context),
        *storageManager->getDataFH()->getPageManager(), &storageManager->getShadowFile());
}

void PrimaryKeyIndex::reclaimStorage(PageAllocator& pageAllocator) const {
    for (auto& hashIndex : hashIndices) {
        hashIndex->reclaimStorage(pageAllocator);
    }
    hashIndexDiskArrays->reclaimStorage(pageAllocator, getDiskArrayFirstHeaderPage());
    if (overflowFile) {
        overflowFile->reclaimStorage(pageAllocator);
    }
    const auto firstHeaderPage = getFirstHeaderPage();
    if (firstHeaderPage != INVALID_PAGE_IDX) {
        pageAllocator.freePageRange({getFirstHeaderPage(), NUM_HEADER_PAGES});
    }
}

page_idx_t PrimaryKeyIndex::getDiskArrayFirstHeaderPage() const {
    const auto firstHeaderPage = getFirstHeaderPage();
    return firstHeaderPage == INVALID_PAGE_IDX ? INVALID_PAGE_IDX :
                                                 firstHeaderPage + NUM_HEADER_PAGES;
}

page_idx_t PrimaryKeyIndex::getFirstHeaderPage() const {
    return storageInfo->cast<PrimaryKeyIndexStorageInfo>().firstHeaderPage;
}

} // namespace storage
} // namespace lbug
