#pragma once

#include "common/types/ku_string.h"

namespace lbug {
namespace common {

struct blob_t {
    ku_string_t value;
};

struct HexFormatConstants {
    // map of integer -> hex value.
    static constexpr const char* HEX_TABLE = "0123456789ABCDEF";
    // reverse map of byte -> integer value, or -1 for invalid hex values.
    static const int HEX_MAP[256];
    static constexpr const uint64_t NUM_BYTES_TO_SHIFT_FOR_FIRST_BYTE = 4;
    static constexpr const uint64_t SECOND_BYTE_MASK = 0x0F;
    static constexpr const char PREFIX[] = "\\x";
    static constexpr const uint64_t PREFIX_LENGTH = 2;
    static constexpr const uint64_t FIRST_BYTE_POS = PREFIX_LENGTH;
    static constexpr const uint64_t SECOND_BYTES_POS = PREFIX_LENGTH + 1;
    static constexpr const uint64_t LENGTH = 4;
};

struct Blob {
    static std::string toString(const uint8_t* value, uint64_t len);

    static inline std::string toString(const blob_t& blob) {
        return toString(blob.value.getData(), blob.value.len);
    }

    static uint64_t getBlobSize(const ku_string_t& blob);

    static uint64_t fromString(const char* str, uint64_t length, uint8_t* resultBuffer);

    template<typename T>
    static inline T getValue(const blob_t& data) {
        return *reinterpret_cast<const T*>(data.value.getData());
    }
    template<typename T>
    // NOLINTNEXTLINE(readability-non-const-parameter): Would cast away qualifiers.
    static inline T getValue(char* data) {
        return *reinterpret_cast<T*>(data);
    }

private:
    static void validateHexCode(const uint8_t* blobStr, uint64_t length, uint64_t curPos);
};

} // namespace common
} // namespace lbug
