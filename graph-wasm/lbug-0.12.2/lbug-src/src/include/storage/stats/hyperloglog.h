// This HyperLogLog implementation is taken from duckdb.
// Source code:
// https://github.com/duckdb/duckdb/blob/main/src/include/duckdb/common/types/hyperloglog.hpp

#pragma once

#include <array>

#include "common/utils.h"

namespace lbug {
namespace storage {

class HyperLogLog {
public:
    static constexpr common::cardinality_t P = 6;
    static constexpr common::cardinality_t Q = 64 - P;
    static constexpr common::cardinality_t M = 1 << P;
    static constexpr double ALPHA = 0.721347520444481703680; // 1 / (2 log(2))

public:
    HyperLogLog() : k{} {} // NOLINT(*-pro-type-member-init)

    //! Algorithm 1
    void insertElement(common::hash_t h) {
        const auto i = h & ((1 << P) - 1);
        h >>= P;
        h |= static_cast<common::hash_t>(1) << Q;
        const uint8_t z = static_cast<uint8_t>(common::CountZeros<common::hash_t>::Trailing(h) + 1);
        update(i, z);
    }

    void update(const common::idx_t& i, const uint8_t& z) { k[i] = std::max<uint8_t>(k[i], z); }

    uint8_t getRegister(const common::idx_t& i) const { return k[i]; }

    common::cardinality_t count() const;

    //! Algorithm 2
    void merge(const HyperLogLog& other);

    void serialize(common::Serializer& serializer) const;
    static HyperLogLog deserialize(common::Deserializer& deserializer);

    //! Algorithm 4
    void extractCounts(uint32_t* c) const;
    //! Algorithm 6
    static int64_t estimateCardinality(const uint32_t* c);

private:
    std::array<uint8_t, M> k;
};

} // namespace storage
} // namespace lbug
