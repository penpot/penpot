#pragma once

#include <memory>

#include "common/static_vector.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/disk_array.h"
#include "storage/index/hash_index_header.h"
#include "storage/index/hash_index_slot.h"
#include "storage/index/hash_index_utils.h"
#include "storage/overflow_file.h"

namespace lbug {
namespace storage {

using visible_func = std::function<bool(common::offset_t)>;

constexpr size_t INDEX_BUFFER_SIZE = 1024;
template<typename T>
using IndexBuffer = common::StaticVector<std::pair<T, common::offset_t>, INDEX_BUFFER_SIZE>;

template<typename T>
using HashIndexType =
    std::conditional_t<std::same_as<T, std::string_view> || std::same_as<T, std::string>,
        common::ku_string_t, T>;

/**
 * Basic index file consists of three disk arrays: indexHeader, primary slots (pSlots), and overflow
 * slots (oSlots).
 *
 * 1. HashIndexHeader contains the current state of the hash tables (level and split information:
 * currentLevel, levelHashMask, higherLevelHashMask, nextSplitSlotId;  key data type).
 *
 * 2. Given a key, it is mapped to one of the pSlots based on its hash value and the level and
 * splitting info. The actual key and value are either stored in the pSlot, or in a chained overflow
 * slots (oSlots) of the pSlot.
 *
 * The slot data structure:
 * Each slot (p/oSlot) consists of a slot header and several entries. The max number of entries in
 * slot is given by HashIndexConstants::SLOT_CAPACITY. The size of the slot is given by
 * (sizeof(SlotHeader) + (SLOT_CAPACITY * sizeof(Entry)).
 *
 * SlotHeader: [numEntries, validityMask, nextOvfSlotId]
 * Entry: [key (fixed sized part), node_offset]
 *
 * 3. oSlots are used to store entries that comes to the designated primary slot that has already
 * been filled to the capacity. Several overflow slots can be chained after the single primary slot
 * as a singly linked link-list. Each slot's SlotHeader has information about the next overflow slot
 * in the chain and also the number of filled entries in that slot.
 *
 *  */

// T is the key type stored in the slots.
// For strings this is different than the type used when inserting/searching
// (see BufferKeyType and Key)
template<typename T>
class InMemHashIndex final {
public:
    using OwnedType = std::conditional_t<std::is_same_v<T, common::ku_string_t>, std::string, T>;
    using KeyType = std::conditional_t<std::is_same_v<T, common::ku_string_t>, std::string_view, T>;
    static_assert(std::is_constructible_v<OwnedType, KeyType>);
    static_assert(std::is_constructible_v<KeyType, OwnedType>);

    static constexpr auto SLOT_CAPACITY = getSlotCapacity<OwnedType>();
    using InMemSlotType = Slot<OwnedType>;

    // Size of the validity mask
    static_assert(SLOT_CAPACITY <= sizeof(SlotHeader().validityMask) * 8);
    static_assert(SLOT_CAPACITY <= std::numeric_limits<entry_pos_t>::max() + 1);

    // sanity check to make sure we aren't accidentally making slots for some types larger than 256
    // bytes.
    static_assert(DiskArray<InMemSlotType>::getAlignedElementSize() <=
                  common::HashIndexConstants::SLOT_CAPACITY_BYTES);

    // the size of Slot depends on the size of T and should always be close to the
    // SLOT_CAPACITY_BYTES
    static_assert(DiskArray<InMemSlotType>::getAlignedElementSize() >
                  common::HashIndexConstants::SLOT_CAPACITY_BYTES / 2);

public:
    explicit InMemHashIndex(MemoryManager& memoryManager, OverflowFileHandle* overflowFileHandle);

    // Reserves space for at least the specified number of elements.
    // This reserves space for numEntries in total, regardless of existing entries in the builder
    void reserve(uint32_t numEntries);
    // Allocates the given number of new slots, ignoo
    void allocateSlots(uint32_t numSlots);

    void reserveSpaceForAppend(uint32_t numNewEntries) {
        reserve(indexHeader.numEntries + numNewEntries);
    }

    // Appends the buffer to the index. Returns the number of values successfully inserted.
    // I.e. if a key fails to insert, its index will be the return value
    size_t append(IndexBuffer<OwnedType>& buffer, uint64_t bufferOffset, visible_func isVisible) {
        reserve(indexHeader.numEntries + buffer.size() - bufferOffset);
        common::hash_t hashes[INDEX_BUFFER_SIZE];
        for (size_t i = bufferOffset; i < buffer.size(); i++) {
            hashes[i] = HashIndexUtils::hash(buffer[i].first);
            auto& [key, value] = buffer[i];
            if (!appendInternal(std::move(key), value, hashes[i], isVisible)) {
                return i - bufferOffset;
            }
        }
        return buffer.size() - bufferOffset;
    }

    bool append(OwnedType&& key, common::offset_t value, visible_func isVisible) {
        reserve(indexHeader.numEntries + 1);
        return appendInternal(std::move(key), value, HashIndexUtils::hash(key), isVisible);
    }
    bool lookup(KeyType key, common::offset_t& result, visible_func isVisible) {
        // This needs to be fast if the builder is empty since this function is always tried
        // when looking up in the persistent hash index
        if (this->indexHeader.numEntries == 0) {
            return false;
        }
        auto hashValue = HashIndexUtils::hash(key);
        auto fingerprint = HashIndexUtils::getFingerprintForHash(hashValue);
        auto slotId = HashIndexUtils::getPrimarySlotIdForHash(this->indexHeader, hashValue);
        SlotIterator iter(slotId, this);
        auto entryPos = findEntry(iter, key, fingerprint, isVisible);
        if (entryPos != SlotHeader::INVALID_ENTRY_POS) {
            result = iter.slot->entries[entryPos].value;
            return true;
        }
        return false;
    }

    uint64_t size() const { return this->indexHeader.numEntries; }
    bool empty() const { return size() == 0; }

    void clear();

    struct SlotIterator {
        explicit SlotIterator(slot_id_t newSlotId, const InMemHashIndex* builder)
            : slotInfo{newSlotId, SlotType::PRIMARY}, slot(builder->getSlot(slotInfo)) {}
        explicit SlotIterator(SlotInfo slotInfo, const InMemHashIndex* builder)
            : slotInfo{slotInfo}, slot(builder->getSlot(slotInfo)) {}
        SlotInfo slotInfo;
        InMemSlotType* slot;
    };

    // Leaves the slot pointer pointing at the last slot to make it easier to add a new one
    bool nextChainedSlot(SlotIterator& iter) const {
        KU_ASSERT(iter.slotInfo.slotType == SlotType::PRIMARY ||
                  iter.slotInfo.slotId != iter.slot->header.nextOvfSlotId);
        if (iter.slot->header.nextOvfSlotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
            iter.slotInfo.slotId = iter.slot->header.nextOvfSlotId;
            iter.slotInfo.slotType = SlotType::OVF;
            iter.slot = getSlot(iter.slotInfo);
            return true;
        }
        return false;
    }

    uint64_t numPrimarySlots() const { return pSlots->size(); }
    uint64_t numOverflowSlots() const { return oSlots->size(); }

    const HashIndexHeader& getIndexHeader() const { return indexHeader; }

    // Deletes key, maintaining gapless structure by replacing it with the last entry in the
    // slot
    bool deleteKey(KeyType key) {
        if (this->indexHeader.numEntries == 0) {
            return false;
        }
        auto hashValue = HashIndexUtils::hash(key);
        auto fingerprint = HashIndexUtils::getFingerprintForHash(hashValue);
        auto slotId = HashIndexUtils::getPrimarySlotIdForHash(this->indexHeader, hashValue);
        SlotIterator iter(slotId, this);
        std::optional<entry_pos_t> deletedPos;
        do {
            for (auto entryPos = 0u; entryPos < SLOT_CAPACITY; entryPos++) {
                if (iter.slot->header.isEntryValid(entryPos) &&
                    iter.slot->header.fingerprints[entryPos] == fingerprint &&
                    equals(key, iter.slot->entries[entryPos].key)) {
                    deletedPos = entryPos;
                    break;
                }
            }
            if (deletedPos.has_value()) {
                break;
            }
        } while (nextChainedSlot(iter));

        if (deletedPos.has_value()) {
            // Find the last valid entry and move it into the deleted position
            auto newIter = iter;
            while (nextChainedSlot(newIter)) {}
            if (newIter.slotInfo != iter.slotInfo ||
                *deletedPos != newIter.slot->header.numEntries() - 1) {
                KU_ASSERT(newIter.slot->header.numEntries() > 0);
                auto lastEntryPos = newIter.slot->header.numEntries() - 1;
                iter.slot->entries[*deletedPos] = newIter.slot->entries[lastEntryPos];
                iter.slot->header.setEntryValid(*deletedPos,
                    newIter.slot->header.fingerprints[lastEntryPos]);
                newIter.slot->header.setEntryInvalid(lastEntryPos);
            } else {
                iter.slot->header.setEntryInvalid(*deletedPos);
            }

            if (newIter.slot->header.numEntries() == 0) {
                reclaimOverflowSlots(SlotIterator(slotId, this));
            }

            return true;
        }
        return false;
    }

private:
    // Assumes that space has already been allocated for the entry
    bool appendInternal(OwnedType&& key, common::offset_t value, common::hash_t hash,
        visible_func isVisible) {
        auto fingerprint = HashIndexUtils::getFingerprintForHash(hash);
        auto slotID = HashIndexUtils::getPrimarySlotIdForHash(this->indexHeader, hash);
        SlotIterator iter(slotID, this);
        // The builder never keeps holes and doesn't support deletions
        // Check the valid entries, then insert at the end if we don't find one which matches
        auto entryPos = findEntry(iter, key, fingerprint, isVisible);
        auto numEntries = iter.slot->header.numEntries();
        if (entryPos != SlotHeader::INVALID_ENTRY_POS) {
            // The key already exists
            return false;
        } else if (numEntries < SLOT_CAPACITY) [[likely]] {
            // The key does not exist and the last slot has free space
            insert(std::move(key), iter.slot, numEntries, value, fingerprint);
            this->indexHeader.numEntries++;
            return true;
        }
        // The last slot is full. Insert a new one
        insertToNewOvfSlot(std::move(key), iter.slot, value, fingerprint);
        this->indexHeader.numEntries++;
        return true;
    }
    InMemSlotType* getSlot(const SlotInfo& slotInfo) const;

    uint32_t allocatePSlots(uint32_t numSlotsToAllocate);
    uint32_t allocateAOSlot();
    /*
     * When a slot is split, we add a new slot, which ends up with an
     * id equal to the slot to split's ID + (1 << header.currentLevel).
     * Values are then rehashed using a hash index which is one bit wider than before,
     * meaning they either stay in the existing slot, or move into the new one.
     */
    void splitSlot();
    // Reclaims empty overflow slots to be re-used, starting from the given slot iterator
    void reclaimOverflowSlots(SlotIterator iter);
    void addFreeOverflowSlot(InMemSlotType& overflowSlot, SlotInfo slotInfo);
    uint64_t countSlots(SlotIterator iter) const;
    // Make sure that the free overflow slot chain is at least as long as the totalSlotsRequired
    void reserveOverflowSlots(uint64_t totalSlotsRequired);

    bool equals(KeyType keyToLookup, const OwnedType& keyInEntry) const {
        return keyToLookup == keyInEntry;
    }

    void insert(OwnedType&& key, InMemSlotType* slot, uint8_t entryPos, common::offset_t value,
        uint8_t fingerprint) {
        KU_ASSERT(HashIndexUtils::getFingerprintForHash(HashIndexUtils::hash(key)) == fingerprint);
        auto& entry = slot->entries[entryPos];
        entry = SlotEntry<OwnedType>(std::move(key), value);
        slot->header.setEntryValid(entryPos, fingerprint);
    }

    void insertToNewOvfSlot(OwnedType&& key, InMemSlotType* previousSlot, common::offset_t offset,
        uint8_t fingerprint) {
        auto newSlotId = allocateAOSlot();
        previousSlot->header.nextOvfSlotId = newSlotId;
        auto newSlot = getSlot(SlotInfo{newSlotId, SlotType::OVF});
        auto entryPos = 0u; // Always insert to the first entry when there is a new slot.
        insert(std::move(key), newSlot, entryPos, offset, fingerprint);
    }

    common::hash_t hashStored(const OwnedType& key) const;
    InMemSlotType* clearNextOverflowAndAdvanceIter(SlotIterator& iter);

    // Finds the entry matching the given key. The iterator will be advanced and will either point
    // to the slot containing the matching entry, or the last slot available
    entry_pos_t findEntry(SlotIterator& iter, KeyType key, uint8_t fingerprint,
        visible_func isVisible) {
        do {
            auto numEntries = iter.slot->header.numEntries();
            KU_ASSERT(numEntries == std::countr_one(iter.slot->header.validityMask));
            for (auto entryPos = 0u; entryPos < numEntries; entryPos++) {
                if (iter.slot->header.fingerprints[entryPos] == fingerprint &&
                    equals(key, iter.slot->entries[entryPos].key) &&
                    isVisible(iter.slot->entries[entryPos].value)) [[unlikely]] {
                    // Value already exists
                    return entryPos;
                }
            }
            if (numEntries < SLOT_CAPACITY) {
                return SlotHeader::INVALID_ENTRY_POS;
            }
        } while (nextChainedSlot(iter));
        return SlotHeader::INVALID_ENTRY_POS;
    }

private:
    // TODO: might be more efficient to use a vector for each slot since this is now only needed
    // in-memory and it would remove the need to handle overflow slots.
    OverflowFileHandle* overflowFileHandle;
    std::unique_ptr<BlockVector<InMemSlotType>> pSlots;
    std::unique_ptr<BlockVector<InMemSlotType>> oSlots;
    HashIndexHeader indexHeader;
    MemoryManager& memoryManager;
    uint64_t numFreeSlots;
};

} // namespace storage
} // namespace lbug
