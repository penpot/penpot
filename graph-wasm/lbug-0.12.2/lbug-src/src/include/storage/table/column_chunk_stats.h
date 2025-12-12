#pragma once

#include "storage/compression/compression.h"
namespace common {
class ValueVector;
}
namespace lbug::storage {
class ColumnChunkData;

struct LBUG_API ColumnChunkStats {
    std::optional<StorageValue> max;
    std::optional<StorageValue> min;

    void update(std::optional<StorageValue> min, std::optional<StorageValue> max,
        common::PhysicalTypeID dataType);
    void update(StorageValue val, common::PhysicalTypeID dataType);
    void update(const common::ValueVector& valueVector, uint64_t offset, uint64_t numValues,
        common::PhysicalTypeID physicalType);
    void update(const ColumnChunkData& data, uint64_t offset, uint64_t numValues,
        common::PhysicalTypeID physicalType);
    void reset();
};

struct MergedColumnChunkStats {
    MergedColumnChunkStats(ColumnChunkStats stats, bool guaranteedNoNulls, bool guaranteedAllNulls)
        : stats(stats), guaranteedNoNulls(guaranteedNoNulls),
          guaranteedAllNulls(guaranteedAllNulls) {}

    ColumnChunkStats stats;
    bool guaranteedNoNulls;
    bool guaranteedAllNulls;

    void merge(const MergedColumnChunkStats& o, common::PhysicalTypeID dataType);
};

} // namespace lbug::storage
