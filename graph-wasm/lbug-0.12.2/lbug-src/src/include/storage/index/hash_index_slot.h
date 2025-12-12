#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <type_traits>

#include "common/assert.h"
#include "common/constants.h"
#include "common/types/types.h"
#include <bit>

namespace lbug {
namespace storage {

using entry_pos_t = uint8_t;
using slot_id_t = uint64_t;

class SlotHeader {
public:
    static const entry_pos_t INVALID_ENTRY_POS = UINT8_MAX;
    static const slot_id_t INVALID_OVERFLOW_SLOT_ID = UINT64_MAX;
    // For a header of 32 bytes.
    // This is smaller than the possible number of entries with an 1-byte key like uint8_t,
    // but the additional size would limit the number of entries for 8-byte keys, so we
    // instead restrict the capacity to 20
    static constexpr uint8_t FINGERPRINT_CAPACITY = 20;

    SlotHeader() : fingerprints{}, validityMask{0}, nextOvfSlotId{INVALID_OVERFLOW_SLOT_ID} {}

    void reset() {
        validityMask = 0;
        nextOvfSlotId = INVALID_OVERFLOW_SLOT_ID;
    }

    inline bool isEntryValid(uint32_t entryPos) const {
        return validityMask & ((uint32_t)1 << entryPos);
    }
    inline void setEntryValid(entry_pos_t entryPos, uint8_t fingerprint) {
        validityMask |= ((uint32_t)1 << entryPos);
        fingerprints[entryPos] = fingerprint;
    }
    inline void setEntryInvalid(entry_pos_t entryPos) {
        validityMask &= ~((uint32_t)1 << entryPos);
    }

    inline entry_pos_t numEntries() const { return std::popcount(validityMask); }

public:
    std::array<uint8_t, FINGERPRINT_CAPACITY> fingerprints;
    uint32_t validityMask;
    slot_id_t nextOvfSlotId;
};
static_assert(std::has_unique_object_representations_v<SlotHeader>);

template<typename T>
struct SlotEntry {
    SlotEntry(T _key, common::offset_t _value) : key{std::move(_key)}, value{_value} {
        // Zero padding, if any
        if constexpr (sizeof(T) + sizeof(common::offset_t) < sizeof(SlotEntry<T>)) {
            auto padding = sizeof(SlotEntry<T>) - sizeof(T) - sizeof(common::offset_t);
            memset(reinterpret_cast<uint8_t*>(&key) + sizeof(T), 0, padding);
            // Assumes that all the padding follows the key
            KU_ASSERT((std::byte*)&key + sizeof(key) + padding == (std::byte*)&value);
        }
    }
    SlotEntry() : SlotEntry(T{}, 0) {}

    T key;
    common::offset_t value;
};

template<typename T>
static constexpr uint8_t getSlotCapacity() {
    return std::min((common::HashIndexConstants::SLOT_CAPACITY_BYTES - sizeof(SlotHeader)) /
                        sizeof(SlotEntry<T>),
        static_cast<size_t>(SlotHeader::FINGERPRINT_CAPACITY));
}

template<typename T>
struct Slot {
    Slot() : header{}, entries{} {}
    SlotHeader header;
    std::array<SlotEntry<T>, getSlotCapacity<T>()> entries;
};

} // namespace storage
} // namespace lbug
