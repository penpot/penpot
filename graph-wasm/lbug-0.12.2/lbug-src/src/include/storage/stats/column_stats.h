#pragma once

#include <optional>

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/vector/value_vector.h"
#include "storage/stats/hyperloglog.h"

namespace lbug {
namespace storage {

class ColumnStats {
public:
    ColumnStats() = default;
    explicit ColumnStats(const common::LogicalType& dataType);
    EXPLICIT_COPY_DEFAULT_MOVE(ColumnStats);

    common::cardinality_t getNumDistinctValues() const { return hll ? hll->count() : 0; }

    void update(const common::ValueVector* vector);

    void merge(const ColumnStats& other) {
        if (hll) {
            KU_ASSERT(other.hll);
            hll->merge(*other.hll);
        };
    }

    void serialize(common::Serializer& serializer) const {
        serializer.writeDebuggingInfo("has_hll");
        serializer.serializeValue(hll.has_value());
        if (hll) {
            serializer.writeDebuggingInfo("hll");
            hll->serialize(serializer);
        }
    }

    static ColumnStats deserialize(common::Deserializer& deserializer) {
        ColumnStats columnStats;
        std::string info;
        deserializer.validateDebuggingInfo(info, "has_hll");
        bool hasHll = false;
        deserializer.deserializeValue(hasHll);
        if (hasHll) {
            deserializer.validateDebuggingInfo(info, "hll");
            columnStats.hll = HyperLogLog::deserialize(deserializer);
        }
        return columnStats;
    }

private:
    ColumnStats(const ColumnStats& other) : hll{other.hll}, hashes{nullptr} {}

private:
    std::optional<HyperLogLog> hll;
    // Preallocated vector for hash values.
    std::unique_ptr<common::ValueVector> hashes;
};

} // namespace storage
} // namespace lbug
