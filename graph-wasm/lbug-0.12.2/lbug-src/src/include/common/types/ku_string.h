#pragma once

#include <cstdint>
#include <cstring>
#include <string>

#include "common/api.h"

namespace lbug {
namespace common {

struct LBUG_API ku_string_t {

    static constexpr uint64_t PREFIX_LENGTH = 4;
    static constexpr uint64_t INLINED_SUFFIX_LENGTH = 8;
    static constexpr uint64_t SHORT_STR_LENGTH = PREFIX_LENGTH + INLINED_SUFFIX_LENGTH;

    uint32_t len;
    uint8_t prefix[PREFIX_LENGTH];
    union {
        uint8_t data[INLINED_SUFFIX_LENGTH];
        uint64_t overflowPtr;
    };

    ku_string_t() : len{0}, prefix{}, overflowPtr{0} {}
    ku_string_t(const char* value, uint64_t length);

    static bool isShortString(uint32_t len) { return len <= SHORT_STR_LENGTH; }

    const uint8_t* getData() const {
        return isShortString(len) ? prefix : reinterpret_cast<uint8_t*>(overflowPtr);
    }

    uint8_t* getDataUnsafe() {
        return isShortString(len) ? prefix : reinterpret_cast<uint8_t*>(overflowPtr);
    }

    // These functions do *NOT* allocate/resize the overflow buffer, it only copies the content and
    // set the length.
    void set(const std::string& value);
    void set(const char* value, uint64_t length);
    void set(const ku_string_t& value);
    void setShortString(const char* value, uint64_t length) {
        this->len = length;
        memcpy(prefix, value, length);
    }
    void setLongString(const char* value, uint64_t length) {
        this->len = length;
        memcpy(prefix, value, PREFIX_LENGTH);
        memcpy(reinterpret_cast<char*>(overflowPtr), value, length);
    }
    void setShortString(const ku_string_t& value) {
        this->len = value.len;
        memcpy(prefix, value.prefix, value.len);
    }
    void setLongString(const ku_string_t& value) {
        this->len = value.len;
        memcpy(prefix, value.prefix, PREFIX_LENGTH);
        memcpy(reinterpret_cast<char*>(overflowPtr), reinterpret_cast<char*>(value.overflowPtr),
            value.len);
    }

    void setFromRawStr(const char* value, uint64_t length) {
        this->len = length;
        if (isShortString(length)) {
            setShortString(value, length);
        } else {
            memcpy(prefix, value, PREFIX_LENGTH);
            overflowPtr = reinterpret_cast<uint64_t>(value);
        }
    }

    std::string getAsShortString() const;
    std::string getAsString() const;
    std::string_view getAsStringView() const;

    bool operator==(const ku_string_t& rhs) const;

    inline bool operator!=(const ku_string_t& rhs) const { return !(*this == rhs); }

    bool operator>(const ku_string_t& rhs) const;

    inline bool operator>=(const ku_string_t& rhs) const { return (*this > rhs) || (*this == rhs); }

    inline bool operator<(const ku_string_t& rhs) const { return !(*this >= rhs); }

    inline bool operator<=(const ku_string_t& rhs) const { return !(*this > rhs); }
};

} // namespace common
} // namespace lbug
