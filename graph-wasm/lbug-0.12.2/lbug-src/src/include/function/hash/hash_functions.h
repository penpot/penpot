#pragma once

#include <cstdint>
#include <functional>
#include <unordered_set>

#include "common/exception/runtime.h"
#include "common/types/int128_t.h"
#include "common/types/interval_t.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "common/types/uint128_t.h"

namespace lbug {
namespace function {

constexpr const uint64_t NULL_HASH = UINT64_MAX;

inline common::hash_t murmurhash64(uint64_t x) {
    // taken from https://nullprogram.com/blog/2018/07/31.
    x ^= x >> 32;
    x *= 0xd6e8feb86659fd93U;
    x ^= x >> 32;
    x *= 0xd6e8feb86659fd93U;
    x ^= x >> 32;
    return x;
}

inline common::hash_t combineHashScalar(const common::hash_t a, const common::hash_t b) {
    return (a * UINT64_C(0xbf58476d1ce4e5b9)) ^ b;
}

struct Hash {
    template<class T>
    static void operation(const T& /*key*/, common::hash_t& /*result*/) {
        // LCOV_EXCL_START
        throw common::RuntimeException(
            "Hash type: " + std::string(typeid(T).name()) + " is not supported.");
        // LCOV_EXCL_STOP
    }

    template<class T>
    static void operation(const T& key, bool isNull, common::hash_t& result) {
        if (isNull) {
            result = NULL_HASH;
            return;
        }
        operation(key, result);
    }
};

struct CombineHash {
    static inline void operation(const common::hash_t& left, const common::hash_t& right,
        common::hash_t& result) {
        result = combineHashScalar(left, right);
    }
};

template<>
inline void Hash::operation(const common::internalID_t& key, common::hash_t& result) {
    result = murmurhash64(key.offset) ^ murmurhash64(key.tableID);
}

template<>
inline void Hash::operation(const bool& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const uint8_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const uint16_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const uint32_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const uint64_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const int64_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const int32_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const int16_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const int8_t& key, common::hash_t& result) {
    result = murmurhash64(key);
}

template<>
inline void Hash::operation(const common::int128_t& key, common::hash_t& result) {
    result = murmurhash64(key.low) ^ murmurhash64(key.high);
}

template<>
inline void Hash::operation(const common::uint128_t& key, common::hash_t& result) {
    result = murmurhash64(key.low) ^ murmurhash64(key.high);
}

template<>
inline void Hash::operation(const double& key, common::hash_t& result) {
    // 0 and -0 are not byte-equivalent, but should have the same hash
    if (key == 0) {
        result = murmurhash64(0);
    } else {
        result = murmurhash64(*reinterpret_cast<const uint64_t*>(&key));
    }
}

template<>
inline void Hash::operation(const float& key, common::hash_t& result) {
    // 0 and -0 are not byte-equivalent, but should have the same hash
    if (key == 0) {
        result = murmurhash64(0);
    } else {
        result = murmurhash64(*reinterpret_cast<const uint32_t*>(&key));
    }
}

template<>
inline void Hash::operation(const std::string_view& key, common::hash_t& result) {
    common::hash_t hashValue = 0;
    auto data64 = reinterpret_cast<const uint64_t*>(key.data());
    for (size_t i = 0u; i < key.size() / 8; i++) {
        auto blockHash = lbug::function::murmurhash64(*(data64 + i));
        hashValue = lbug::function::combineHashScalar(hashValue, blockHash);
    }
    uint64_t last = 0;
    for (size_t i = 0u; i < key.size() % 8; i++) {
        last |= static_cast<uint64_t>(key[key.size() / 8 * 8 + i]) << i * 8;
    }
    hashValue = lbug::function::combineHashScalar(hashValue, lbug::function::murmurhash64(last));
    result = hashValue;
}

template<>
inline void Hash::operation(const std::string& key, common::hash_t& result) {
    Hash::operation(std::string_view(key), result);
}

template<>
inline void Hash::operation(const common::ku_string_t& key, common::hash_t& result) {
    Hash::operation(key.getAsStringView(), result);
}

template<>
inline void Hash::operation(const common::interval_t& key, common::hash_t& result) {
    result = combineHashScalar(murmurhash64(key.months),
        combineHashScalar(murmurhash64(key.days), murmurhash64(key.micros)));
}

template<>
inline void Hash::operation(const std::unordered_set<std::string>& key, common::hash_t& result) {
    for (auto&& s : key) {
        result ^= std::hash<std::string>()(s);
    }
}

struct InternalIDHasher {
    std::size_t operator()(const common::internalID_t& internalID) const {
        common::hash_t result = 0;
        function::Hash::operation<common::internalID_t>(internalID, result);
        return result;
    }
};

} // namespace function
} // namespace lbug
