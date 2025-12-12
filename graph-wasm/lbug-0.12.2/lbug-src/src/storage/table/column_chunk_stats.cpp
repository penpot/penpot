#include "storage/table/column_chunk_stats.h"

#include "common/type_utils.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace storage {

void ColumnChunkStats::update(const ColumnChunkData& data, uint64_t offset, uint64_t numValues,
    common::PhysicalTypeID physicalType) {
    const bool isStorageValueType =
        common::TypeUtils::visit(physicalType, []<typename T>(T) { return StorageValueType<T>; });
    if (isStorageValueType || physicalType == common::PhysicalTypeID::INTERNAL_ID) {
        auto [minVal, maxVal] = getMinMaxStorageValue(data, offset, numValues, physicalType);
        update(minVal, maxVal, physicalType);
    }
}

void ColumnChunkStats::update(const common::ValueVector& data, uint64_t offset, uint64_t numValues,
    common::PhysicalTypeID physicalType) {
    const bool isStorageValueType =
        common::TypeUtils::visit(physicalType, []<typename T>(T) { return StorageValueType<T>; });
    if (isStorageValueType || physicalType == common::PhysicalTypeID::INTERNAL_ID) {
        auto [minVal, maxVal] = getMinMaxStorageValue(data, offset, numValues, physicalType);
        update(minVal, maxVal, physicalType);
    }
}

void ColumnChunkStats::update(std::optional<StorageValue> newMin,
    std::optional<StorageValue> newMax, common::PhysicalTypeID dataType) {
    if (!min.has_value() || (newMin.has_value() && min->gt(*newMin, dataType))) {
        min = newMin;
    }
    if (!max.has_value() || (newMax.has_value() && newMax->gt(*max, dataType))) {
        max = newMax;
    }
}

void ColumnChunkStats::update(StorageValue val, common::PhysicalTypeID dataType) {
    if (!min.has_value() || min->gt(val, dataType)) {
        min = val;
    }
    if (!max.has_value() || val.gt(*max, dataType)) {
        max = val;
    }
}

void ColumnChunkStats::reset() {
    *this = {};
}

void MergedColumnChunkStats::merge(const MergedColumnChunkStats& o,
    common::PhysicalTypeID dataType) {
    stats.update(o.stats.min, o.stats.max, dataType);
    guaranteedNoNulls = guaranteedNoNulls && o.guaranteedNoNulls;
    guaranteedAllNulls = guaranteedAllNulls && o.guaranteedAllNulls;
}

} // namespace storage
} // namespace lbug
