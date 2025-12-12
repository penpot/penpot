#pragma once

#include "hash_index_slot.h"

namespace lbug {
namespace storage {

struct HashIndexHeaderOnDisk {
    explicit HashIndexHeaderOnDisk()
        : nextSplitSlotId{0}, numEntries{0},
          firstFreeOverflowSlotId{SlotHeader::INVALID_OVERFLOW_SLOT_ID}, currentLevel{0} {}
    slot_id_t nextSplitSlotId;
    uint64_t numEntries;
    slot_id_t firstFreeOverflowSlotId;
    uint8_t currentLevel;
    uint8_t _padding[7]{};
};
static_assert(std::has_unique_object_representations_v<HashIndexHeaderOnDisk>);

class HashIndexHeader {
public:
    explicit HashIndexHeader()
        : currentLevel{1}, levelHashMask{1}, higherLevelHashMask{3}, nextSplitSlotId{0},
          numEntries{0}, firstFreeOverflowSlotId{SlotHeader::INVALID_OVERFLOW_SLOT_ID} {}

    explicit HashIndexHeader(const HashIndexHeaderOnDisk& onDiskHeader)
        : currentLevel{onDiskHeader.currentLevel}, levelHashMask{(1ull << this->currentLevel) - 1},
          higherLevelHashMask{(1ull << (this->currentLevel + 1)) - 1},
          nextSplitSlotId{onDiskHeader.nextSplitSlotId}, numEntries{onDiskHeader.numEntries},
          firstFreeOverflowSlotId{onDiskHeader.firstFreeOverflowSlotId} {}

    inline void incrementLevel() {
        currentLevel++;
        nextSplitSlotId = 0;
        levelHashMask = (1 << currentLevel) - 1;
        higherLevelHashMask = (1 << (currentLevel + 1)) - 1;
    }
    inline void incrementNextSplitSlotId() {
        if (nextSplitSlotId < (1ull << currentLevel) - 1) {
            nextSplitSlotId++;
        } else {
            incrementLevel();
        }
    }

    inline void write(HashIndexHeaderOnDisk& onDiskHeader) const {
        onDiskHeader.currentLevel = currentLevel;
        onDiskHeader.nextSplitSlotId = nextSplitSlotId;
        onDiskHeader.numEntries = numEntries;
        onDiskHeader.firstFreeOverflowSlotId = firstFreeOverflowSlotId;
    }

public:
    uint64_t currentLevel;
    uint64_t levelHashMask;
    uint64_t higherLevelHashMask;
    // Id of the next slot to split when resizing the hash index
    slot_id_t nextSplitSlotId;
    uint64_t numEntries;
    // Id of the first in a chain of empty overflow slots which have been reclaimed during slot
    // splitting. The nextOvfSlotId field in the slot's header indicates the next slot in the chain.
    // These slots should be used first when allocating new overflow slots
    // TODO(bmwinger): Make use of this in the on-disk hash index
    slot_id_t firstFreeOverflowSlotId;
};

} // namespace storage
} // namespace lbug
