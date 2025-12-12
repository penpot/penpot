#pragma once

#include <cstdint>
#include <memory>

#include "common/assert.h"
#include <span>

namespace lbug {
namespace common {

class ArrowNullMaskTree;
class Serializer;
class Deserializer;

constexpr uint64_t NULL_BITMASKS_WITH_SINGLE_ONE[64] = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80,
    0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000, 0x10000, 0x20000, 0x40000, 0x80000,
    0x100000, 0x200000, 0x400000, 0x800000, 0x1000000, 0x2000000, 0x4000000, 0x8000000, 0x10000000,
    0x20000000, 0x40000000, 0x80000000, 0x100000000, 0x200000000, 0x400000000, 0x800000000,
    0x1000000000, 0x2000000000, 0x4000000000, 0x8000000000, 0x10000000000, 0x20000000000,
    0x40000000000, 0x80000000000, 0x100000000000, 0x200000000000, 0x400000000000, 0x800000000000,
    0x1000000000000, 0x2000000000000, 0x4000000000000, 0x8000000000000, 0x10000000000000,
    0x20000000000000, 0x40000000000000, 0x80000000000000, 0x100000000000000, 0x200000000000000,
    0x400000000000000, 0x800000000000000, 0x1000000000000000, 0x2000000000000000,
    0x4000000000000000, 0x8000000000000000};
constexpr uint64_t NULL_BITMASKS_WITH_SINGLE_ZERO[64] = {0xfffffffffffffffe, 0xfffffffffffffffd,
    0xfffffffffffffffb, 0xfffffffffffffff7, 0xffffffffffffffef, 0xffffffffffffffdf,
    0xffffffffffffffbf, 0xffffffffffffff7f, 0xfffffffffffffeff, 0xfffffffffffffdff,
    0xfffffffffffffbff, 0xfffffffffffff7ff, 0xffffffffffffefff, 0xffffffffffffdfff,
    0xffffffffffffbfff, 0xffffffffffff7fff, 0xfffffffffffeffff, 0xfffffffffffdffff,
    0xfffffffffffbffff, 0xfffffffffff7ffff, 0xffffffffffefffff, 0xffffffffffdfffff,
    0xffffffffffbfffff, 0xffffffffff7fffff, 0xfffffffffeffffff, 0xfffffffffdffffff,
    0xfffffffffbffffff, 0xfffffffff7ffffff, 0xffffffffefffffff, 0xffffffffdfffffff,
    0xffffffffbfffffff, 0xffffffff7fffffff, 0xfffffffeffffffff, 0xfffffffdffffffff,
    0xfffffffbffffffff, 0xfffffff7ffffffff, 0xffffffefffffffff, 0xffffffdfffffffff,
    0xffffffbfffffffff, 0xffffff7fffffffff, 0xfffffeffffffffff, 0xfffffdffffffffff,
    0xfffffbffffffffff, 0xfffff7ffffffffff, 0xffffefffffffffff, 0xffffdfffffffffff,
    0xffffbfffffffffff, 0xffff7fffffffffff, 0xfffeffffffffffff, 0xfffdffffffffffff,
    0xfffbffffffffffff, 0xfff7ffffffffffff, 0xffefffffffffffff, 0xffdfffffffffffff,
    0xffbfffffffffffff, 0xff7fffffffffffff, 0xfeffffffffffffff, 0xfdffffffffffffff,
    0xfbffffffffffffff, 0xf7ffffffffffffff, 0xefffffffffffffff, 0xdfffffffffffffff,
    0xbfffffffffffffff, 0x7fffffffffffffff};

const uint64_t NULL_LOWER_MASKS[65] = {0x0, 0x1, 0x3, 0x7, 0xf, 0x1f, 0x3f, 0x7f, 0xff, 0x1ff,
    0x3ff, 0x7ff, 0xfff, 0x1fff, 0x3fff, 0x7fff, 0xffff, 0x1ffff, 0x3ffff, 0x7ffff, 0xfffff,
    0x1fffff, 0x3fffff, 0x7fffff, 0xffffff, 0x1ffffff, 0x3ffffff, 0x7ffffff, 0xfffffff, 0x1fffffff,
    0x3fffffff, 0x7fffffff, 0xffffffff, 0x1ffffffff, 0x3ffffffff, 0x7ffffffff, 0xfffffffff,
    0x1fffffffff, 0x3fffffffff, 0x7fffffffff, 0xffffffffff, 0x1ffffffffff, 0x3ffffffffff,
    0x7ffffffffff, 0xfffffffffff, 0x1fffffffffff, 0x3fffffffffff, 0x7fffffffffff, 0xffffffffffff,
    0x1ffffffffffff, 0x3ffffffffffff, 0x7ffffffffffff, 0xfffffffffffff, 0x1fffffffffffff,
    0x3fffffffffffff, 0x7fffffffffffff, 0xffffffffffffff, 0x1ffffffffffffff, 0x3ffffffffffffff,
    0x7ffffffffffffff, 0xfffffffffffffff, 0x1fffffffffffffff, 0x3fffffffffffffff,
    0x7fffffffffffffff, 0xffffffffffffffff};
const uint64_t NULL_HIGH_MASKS[65] = {0x0, 0x8000000000000000, 0xc000000000000000,
    0xe000000000000000, 0xf000000000000000, 0xf800000000000000, 0xfc00000000000000,
    0xfe00000000000000, 0xff00000000000000, 0xff80000000000000, 0xffc0000000000000,
    0xffe0000000000000, 0xfff0000000000000, 0xfff8000000000000, 0xfffc000000000000,
    0xfffe000000000000, 0xffff000000000000, 0xffff800000000000, 0xffffc00000000000,
    0xffffe00000000000, 0xfffff00000000000, 0xfffff80000000000, 0xfffffc0000000000,
    0xfffffe0000000000, 0xffffff0000000000, 0xffffff8000000000, 0xffffffc000000000,
    0xffffffe000000000, 0xfffffff000000000, 0xfffffff800000000, 0xfffffffc00000000,
    0xfffffffe00000000, 0xffffffff00000000, 0xffffffff80000000, 0xffffffffc0000000,
    0xffffffffe0000000, 0xfffffffff0000000, 0xfffffffff8000000, 0xfffffffffc000000,
    0xfffffffffe000000, 0xffffffffff000000, 0xffffffffff800000, 0xffffffffffc00000,
    0xffffffffffe00000, 0xfffffffffff00000, 0xfffffffffff80000, 0xfffffffffffc0000,
    0xfffffffffffe0000, 0xffffffffffff0000, 0xffffffffffff8000, 0xffffffffffffc000,
    0xffffffffffffe000, 0xfffffffffffff000, 0xfffffffffffff800, 0xfffffffffffffc00,
    0xfffffffffffffe00, 0xffffffffffffff00, 0xffffffffffffff80, 0xffffffffffffffc0,
    0xffffffffffffffe0, 0xfffffffffffffff0, 0xfffffffffffffff8, 0xfffffffffffffffc,
    0xfffffffffffffffe, 0xffffffffffffffff};

class LBUG_API NullMask {
public:
    static constexpr uint64_t NO_NULL_ENTRY = 0;
    static constexpr uint64_t ALL_NULL_ENTRY = ~uint64_t(NO_NULL_ENTRY);
    static constexpr uint64_t NUM_BITS_PER_NULL_ENTRY_LOG2 = 6;
    static constexpr uint64_t NUM_BITS_PER_NULL_ENTRY = (uint64_t)1 << NUM_BITS_PER_NULL_ENTRY_LOG2;
    static constexpr uint64_t NUM_BYTES_PER_NULL_ENTRY = NUM_BITS_PER_NULL_ENTRY >> 3;

    // For creating a managed null mask
    explicit NullMask(uint64_t capacity) : mayContainNulls{false} {
        auto numNullEntries = (capacity + NUM_BITS_PER_NULL_ENTRY - 1) / NUM_BITS_PER_NULL_ENTRY;
        buffer = std::make_unique<uint64_t[]>(numNullEntries);
        data = std::span(buffer.get(), numNullEntries);
        std::fill(data.begin(), data.end(), NO_NULL_ENTRY);
    }

    // For creating a null mask using existing data
    explicit NullMask(std::span<uint64_t> nullData, bool mayContainNulls)
        : data{nullData}, buffer{}, mayContainNulls{mayContainNulls} {}

    inline void setAllNonNull() {
        if (!mayContainNulls) {
            return;
        }
        std::fill(data.begin(), data.end(), NO_NULL_ENTRY);
        mayContainNulls = false;
    }
    inline void setAllNull() {
        std::fill(data.begin(), data.end(), ALL_NULL_ENTRY);
        mayContainNulls = true;
    }

    inline bool hasNoNullsGuarantee() const { return !mayContainNulls; }
    uint64_t countNulls() const;

    static void setNull(uint64_t* nullEntries, uint32_t pos, bool isNull);
    inline void setNull(uint32_t pos, bool isNull) {
        KU_ASSERT(pos < getNumNullBits(data));
        setNull(data.data(), pos, isNull);
        if (isNull) {
            mayContainNulls = true;
        }
    }

    static inline bool isNull(const uint64_t* nullEntries, uint32_t pos) {
        auto [entryPos, bitPosInEntry] = getNullEntryAndBitPos(pos);
        return nullEntries[entryPos] & NULL_BITMASKS_WITH_SINGLE_ONE[bitPosInEntry];
    }

    static uint64_t getNumNullBits(std::span<uint64_t> data) {
        return data.size() * NullMask::NUM_BITS_PER_NULL_ENTRY;
    }

    inline bool isNull(uint32_t pos) const {
        KU_ASSERT(pos < getNumNullBits(data));
        return isNull(data.data(), pos);
    }

    // const because updates to the data must set mayContainNulls if any value
    // becomes non-null
    // Modifying the underlying data should be done with setNull or copyFromNullData
    inline const uint64_t* getData() const { return data.data(); }

    static inline uint64_t getNumNullEntries(uint64_t numNullBits) {
        return (numNullBits >> NUM_BITS_PER_NULL_ENTRY_LOG2) +
               ((numNullBits - (numNullBits << NUM_BITS_PER_NULL_ENTRY_LOG2)) == 0 ? 0 : 1);
    }

    // Copies bitpacked null flags from one buffer to another, starting at an arbitrary bit
    // offset and preserving adjacent bits.
    //
    // returns true if we have copied a nullBit with value 1 (indicates a null value) to
    // dstNullEntries.
    static bool copyNullMask(const uint64_t* srcNullEntries, uint64_t srcOffset,
        uint64_t* dstNullEntries, uint64_t dstOffset, uint64_t numBitsToCopy, bool invert = false);

    inline bool copyFrom(const NullMask& nullMask, uint64_t srcOffset, uint64_t dstOffset,
        uint64_t numBitsToCopy, bool invert = false) {
        if (nullMask.hasNoNullsGuarantee()) {
            setNullFromRange(dstOffset, numBitsToCopy, invert);
            return invert;
        } else {
            return copyFromNullBits(nullMask.getData(), srcOffset, dstOffset, numBitsToCopy,
                invert);
        }
    }
    bool copyFromNullBits(const uint64_t* srcNullEntries, uint64_t srcOffset, uint64_t dstOffset,
        uint64_t numBitsToCopy, bool invert = false);

    // Sets the given number of bits to null (if isNull is true) or non-null (if isNull is false),
    // starting at the offset
    static void setNullRange(uint64_t* nullEntries, uint64_t offset, uint64_t numBitsToSet,
        bool isNull);

    void setNullFromRange(uint64_t offset, uint64_t numBitsToSet, bool isNull);

    void resize(uint64_t capacity);

    void operator|=(const NullMask& other);

    // Fast calculation of the minimum and maximum null values
    // (essentially just three states, all null, all non-null and some null)
    static std::pair<bool, bool> getMinMax(const uint64_t* nullEntries, uint64_t offset,
        uint64_t numValues);

private:
    static inline std::pair<uint64_t, uint64_t> getNullEntryAndBitPos(uint64_t pos) {
        auto nullEntryPos = pos >> NUM_BITS_PER_NULL_ENTRY_LOG2;
        return std::make_pair(nullEntryPos,
            pos - (nullEntryPos << NullMask::NUM_BITS_PER_NULL_ENTRY_LOG2));
    }

    static bool copyUnaligned(const uint64_t* srcNullEntries, uint64_t srcOffset,
        uint64_t* dstNullEntries, uint64_t dstOffset, uint64_t numBitsToCopy, bool invert = false);

private:
    std::span<uint64_t> data;
    std::unique_ptr<uint64_t[]> buffer;
    bool mayContainNulls;
};

} // namespace common
} // namespace lbug
