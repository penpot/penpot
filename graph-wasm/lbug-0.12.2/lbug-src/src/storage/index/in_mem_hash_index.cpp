#include "storage/index/in_mem_hash_index.h"

#include <cstdint>
#include <cstring>

#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/disk_array.h"
#include "storage/index/hash_index_header.h"
#include "storage/index/hash_index_slot.h"
#include "storage/index/hash_index_utils.h"
#include "storage/overflow_file.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

template<typename T>
InMemHashIndex<T>::InMemHashIndex(MemoryManager& memoryManager,
    OverflowFileHandle* overflowFileHandle)
    : overflowFileHandle(overflowFileHandle),
      pSlots{std::make_unique<BlockVector<InMemSlotType>>(memoryManager)},
      oSlots{std::make_unique<BlockVector<InMemSlotType>>(memoryManager)}, indexHeader{},
      memoryManager{memoryManager}, numFreeSlots{0} {
    // Match HashIndex in allocating at least one page of slots so that we don't split within the
    // same page
    allocateSlots(LBUG_PAGE_SIZE / pSlots->getAlignedElementSize());
}

template<typename T>
void InMemHashIndex<T>::clear() {
    indexHeader = HashIndexHeader();
    pSlots = std::make_unique<BlockVector<InMemSlotType>>(memoryManager);
    oSlots = std::make_unique<BlockVector<InMemSlotType>>(memoryManager);
    allocateSlots(LBUG_PAGE_SIZE / pSlots->getAlignedElementSize());
}

template<typename T>
void InMemHashIndex<T>::allocateSlots(uint32_t newNumSlots) {
    // Allocate memory before updating the header in case the memory allocation fails
    auto existingSlots = pSlots->size();
    if (newNumSlots > existingSlots) {
        allocatePSlots(newNumSlots - existingSlots);
    }
    auto numSlotsOfCurrentLevel = 1u << this->indexHeader.currentLevel;
    while ((numSlotsOfCurrentLevel << 1) <= newNumSlots) {
        this->indexHeader.incrementLevel();
        numSlotsOfCurrentLevel <<= 1;
    }
    if (newNumSlots >= numSlotsOfCurrentLevel) {
        this->indexHeader.nextSplitSlotId = newNumSlots - numSlotsOfCurrentLevel;
    }
}

template<typename T>
void InMemHashIndex<T>::reserve(uint32_t numEntries_) {
    slot_id_t numRequiredEntries = HashIndexUtils::getNumRequiredEntries(numEntries_);
    auto numRequiredSlots = (numRequiredEntries + SLOT_CAPACITY - 1) / SLOT_CAPACITY;
    if (numRequiredSlots <= pSlots->size()) {
        return;
    }
    if (indexHeader.numEntries == 0) {
        allocateSlots(numRequiredSlots);
    } else {
        while (pSlots->size() < numRequiredSlots) {
            splitSlot();
        }
    }
}

template<typename T>
uint64_t InMemHashIndex<T>::countSlots(SlotIterator iter) const {
    if (iter.slotInfo.slotType == SlotType::OVF &&
        iter.slotInfo.slotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
        return 0;
    }
    uint64_t count = 1;
    while (nextChainedSlot(iter)) {
        count++;
    }
    return count;
}

template<typename T>
void InMemHashIndex<T>::reserveOverflowSlots(uint64_t totalSlotsRequired) {
    // Make sure we have enough free slots to do the split without having to allocate more
    // Any unused ones will just stay in the free slot chain and will be reused later
    SlotInfo newSlot{oSlots->size(), SlotType::OVF};
    if (totalSlotsRequired > numFreeSlots) {
        oSlots->resize(oSlots->size() + totalSlotsRequired - numFreeSlots);
        for (uint64_t i = 0; i < totalSlotsRequired - numFreeSlots; i++) {
            auto slot = getSlot(newSlot);
            addFreeOverflowSlot(*slot, newSlot);
            newSlot.slotId++;
        }
    }
}

template<typename T>
void InMemHashIndex<T>::splitSlot() {
    // Add new slot
    allocatePSlots(1);

    // Rehash the entries in the slot to split
    SlotIterator originalSlot(indexHeader.nextSplitSlotId, this);
    // Reserve enough overflow slots to be able to finish splitting without allocating any new
    // memory Otherwise we run the risk of leaving the hash index in an invalid state if we fail to
    // allocate a new overflow slot
    // TODO(bmwinger): If we split slots backwards instead of forwards we would need to reserve just
    // one slot Since we could then reclaim slots that have been emptied. That would require making
    // the slots doubly-linked
    reserveOverflowSlots(countSlots(originalSlot));

    // Use a separate iterator to track the first empty position so that the gapless entries can
    // be maintained
    SlotIterator originalSlotForInsert(indexHeader.nextSplitSlotId, this);
    auto entryPosToInsert = 0u;
    SlotIterator newSlot(pSlots->size() - 1, this);
    entry_pos_t newSlotPos = 0;
    bool gaps = false;
    do {
        for (auto entryPos = 0u; entryPos < SLOT_CAPACITY; entryPos++) {
            if (!originalSlot.slot->header.isEntryValid(entryPos)) {
                // Check that this function leaves no gaps
                KU_ASSERT(originalSlot.slot->header.numEntries() ==
                          std::countr_one(originalSlot.slot->header.validityMask));
                // There should be no gaps, so when we encounter an invalid entry we can return
                // early
                reclaimOverflowSlots(originalSlotForInsert);
                indexHeader.incrementNextSplitSlotId();
                return;
            }
            const auto& entry = originalSlot.slot->entries[entryPos];
            const auto& hash = this->hashStored(originalSlot.slot->entries[entryPos].key);
            const auto fingerprint = HashIndexUtils::getFingerprintForHash(hash);
            const auto newSlotId = hash & indexHeader.higherLevelHashMask;
            if (newSlotId != indexHeader.nextSplitSlotId) {
                if (newSlotPos >= SLOT_CAPACITY) {
                    auto newOvfSlotId = allocateAOSlot();
                    newSlot.slot->header.nextOvfSlotId = newOvfSlotId;
                    [[maybe_unused]] bool hadNextSlot = nextChainedSlot(newSlot);
                    KU_ASSERT(hadNextSlot);
                    newSlotPos = 0;
                }
                newSlot.slot->entries[newSlotPos] = entry;
                newSlot.slot->header.setEntryValid(newSlotPos, fingerprint);
                originalSlot.slot->header.setEntryInvalid(entryPos);
                newSlotPos++;
                gaps = true;
            } else if (gaps) {
                // If we have created a gap previously, move the entry to the first gap to avoid
                // leaving gaps
                while (originalSlotForInsert.slot->header.isEntryValid(entryPosToInsert)) {
                    entryPosToInsert++;
                    if (entryPosToInsert >= SLOT_CAPACITY) {
                        entryPosToInsert = 0;
                        // There should always be another slot since we can't split more entries
                        // than there were to begin with
                        [[maybe_unused]] bool hadNextSlot = nextChainedSlot(originalSlotForInsert);
                        KU_ASSERT(hadNextSlot);
                    }
                }
                originalSlotForInsert.slot->entries[entryPosToInsert] = entry;
                originalSlotForInsert.slot->header.setEntryValid(entryPosToInsert, fingerprint);
                originalSlot.slot->header.setEntryInvalid(entryPos);
            }
        }
        KU_ASSERT(originalSlot.slot->header.numEntries() ==
                  std::countr_one(originalSlot.slot->header.validityMask));
    } while (nextChainedSlot(originalSlot));

    reclaimOverflowSlots(originalSlotForInsert);
    indexHeader.incrementNextSplitSlotId();
}

template<typename T>
void InMemHashIndex<T>::addFreeOverflowSlot(InMemSlotType& overflowSlot, SlotInfo slotInfo) {
    // This function should only be called on slots that can be directly inserted into the free slot
    // list
    KU_ASSERT(slotInfo.slotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID);
    KU_ASSERT(overflowSlot.header.nextOvfSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID);
    KU_ASSERT(slotInfo.slotType == SlotType::OVF);
    overflowSlot.header.nextOvfSlotId = indexHeader.firstFreeOverflowSlotId;
    indexHeader.firstFreeOverflowSlotId = slotInfo.slotId;
    numFreeSlots++;
}

template<typename T>
void InMemHashIndex<T>::reclaimOverflowSlots(SlotIterator iter) {
    // Reclaim empty overflow slots at the end of the chain.
    // This saves the cost of having to iterate over them, and reduces memory usage by letting them
    // be used instead of allocating new slots
    if (iter.slot->header.nextOvfSlotId != SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
        // Skip past the last non-empty entry
        InMemSlotType* lastNonEmptySlot = iter.slot;
        while (iter.slot->header.numEntries() > 0 || iter.slotInfo.slotType == SlotType::PRIMARY) {
            lastNonEmptySlot = iter.slot;
            if (!nextChainedSlot(iter)) {
                iter.slotInfo = HashIndexUtils::INVALID_OVF_INFO;
                break;
            }
        }
        lastNonEmptySlot->header.nextOvfSlotId = SlotHeader::INVALID_OVERFLOW_SLOT_ID;
        while (iter.slotInfo != HashIndexUtils::INVALID_OVF_INFO) {
            // Remove empty overflow slots from slot chain
            KU_ASSERT(iter.slot->header.numEntries() == 0);
            auto slotInfo = iter.slotInfo;
            auto slot = clearNextOverflowAndAdvanceIter(iter);
            if (slotInfo.slotType == SlotType::OVF) {
                // Insert empty slot into free slot chain
                addFreeOverflowSlot(*slot, slotInfo);
            }
        }
    }
}

template<typename T>
InMemHashIndex<T>::InMemSlotType* InMemHashIndex<T>::clearNextOverflowAndAdvanceIter(
    SlotIterator& iter) {
    auto originalSlot = iter.slot;
    auto nextOverflowSlot = iter.slot->header.nextOvfSlotId;
    iter.slot->header.nextOvfSlotId = SlotHeader::INVALID_OVERFLOW_SLOT_ID;
    iter.slotInfo = SlotInfo{nextOverflowSlot, SlotType::OVF};
    if (nextOverflowSlot != SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
        iter.slot = getSlot(iter.slotInfo);
    }
    return originalSlot;
}

template<typename T>
uint32_t InMemHashIndex<T>::allocatePSlots(uint32_t numSlotsToAllocate) {
    auto oldNumSlots = pSlots->size();
    auto newNumSlots = oldNumSlots + numSlotsToAllocate;
    pSlots->resize(newNumSlots);
    return oldNumSlots;
}

template<typename T>
uint32_t InMemHashIndex<T>::allocateAOSlot() {
    if (indexHeader.firstFreeOverflowSlotId == SlotHeader::INVALID_OVERFLOW_SLOT_ID) {
        auto oldNumSlots = oSlots->size();
        auto newNumSlots = oldNumSlots + 1;
        oSlots->resize(newNumSlots);
        return oldNumSlots;
    } else {
        auto freeOSlotId = indexHeader.firstFreeOverflowSlotId;
        auto& slot = (*oSlots)[freeOSlotId];
        // Remove slot from the free slot chain
        indexHeader.firstFreeOverflowSlotId = slot.header.nextOvfSlotId;
        KU_ASSERT(slot.header.numEntries() == 0);
        slot.header.nextOvfSlotId = SlotHeader::INVALID_OVERFLOW_SLOT_ID;
        KU_ASSERT(numFreeSlots > 0);
        numFreeSlots--;
        return freeOSlotId;
    }
}

template<typename T>
InMemHashIndex<T>::InMemSlotType* InMemHashIndex<T>::getSlot(const SlotInfo& slotInfo) const {
    if (slotInfo.slotType == SlotType::PRIMARY) {
        return &pSlots->operator[](slotInfo.slotId);
    } else {
        return &oSlots->operator[](slotInfo.slotId);
    }
}

template<typename T>
common::hash_t InMemHashIndex<T>::hashStored(const InMemHashIndex<T>::OwnedType& key) const {
    return HashIndexUtils::hash(key);
}

template class InMemHashIndex<int64_t>;
template class InMemHashIndex<int32_t>;
template class InMemHashIndex<int16_t>;
template class InMemHashIndex<int8_t>;
template class InMemHashIndex<uint64_t>;
template class InMemHashIndex<uint32_t>;
template class InMemHashIndex<uint16_t>;
template class InMemHashIndex<uint8_t>;
template class InMemHashIndex<double>;
template class InMemHashIndex<float>;
template class InMemHashIndex<int128_t>;
template class InMemHashIndex<uint128_t>;
template class InMemHashIndex<ku_string_t>;

} // namespace storage
} // namespace lbug
