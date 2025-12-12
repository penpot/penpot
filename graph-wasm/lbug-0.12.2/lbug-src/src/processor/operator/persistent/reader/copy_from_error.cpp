#include "processor/operator/persistent/reader/copy_from_error.h"

#include "common/vector/value_vector.h"
#include "storage/table/column_chunk_data.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

template<DataSource T>
static PhysicalTypeID getPhysicalTypeFromDataSource(T* data) {
    if constexpr (std::is_same_v<T, storage::ColumnChunkData>) {
        return data->getDataType().getPhysicalType();
    } else if constexpr (std::is_same_v<T, ValueVector>) {
        return data->dataType.getPhysicalType();
    } else {
        KU_UNREACHABLE;
    }
}

template<DataSource T>
WarningSourceData::DataType getValueFromData(T* data, common::idx_t pos) {
    // avoid using TypeUtils::visit here to avoid the overhead from constructing a capturing lambda
    switch (getPhysicalTypeFromDataSource(data)) {
    case common::PhysicalTypeID::UINT64:
        return data->template getValue<uint64_t>(pos);
    case common::PhysicalTypeID::UINT32:
        return data->template getValue<uint32_t>(pos);
    default:
        KU_UNREACHABLE;
    }
}

WarningSourceData::WarningSourceData(uint64_t numValues) : numValues(numValues) {
    KU_ASSERT(numValues <= values.size());
}

template<DataSource T>
WarningSourceData WarningSourceData::constructFromData(const std::vector<T*>& data,
    common::idx_t pos) {
    KU_ASSERT(data.size() >= CopyConstants::SHARED_WARNING_DATA_NUM_COLUMNS &&
              data.size() <= CopyConstants::MAX_NUM_WARNING_DATA_COLUMNS);
    WarningSourceData ret{data.size()};
    for (idx_t i = 0; i < data.size(); ++i) {
        ret.values[i] = getValueFromData(data[i], pos);
    }
    return ret;
}

uint64_t WarningSourceData::getBlockIdx() const {
    return std::get<uint64_t>(values[BLOCK_IDX_IDX]);
}
uint32_t WarningSourceData::getOffsetInBlock() const {
    return std::get<uint32_t>(values[OFFSET_IN_BLOCK_IDX]);
}

template WarningSourceData WarningSourceData::constructFromData<storage::ColumnChunkData>(
    const std::vector<storage::ColumnChunkData*>& data, common::idx_t pos);
template WarningSourceData WarningSourceData::constructFromData<ValueVector>(
    const std::vector<ValueVector*>& data, common::idx_t pos);

CopyFromFileError::CopyFromFileError(std::string message, WarningSourceData warningData,
    bool completedLine, bool mustThrow)
    : message(std::move(message)), completedLine(completedLine), warningData(warningData),
      mustThrow(mustThrow) {}

bool CopyFromFileError::operator<(const CopyFromFileError& o) const {
    if (warningData.getBlockIdx() == o.warningData.getBlockIdx()) {
        return warningData.getOffsetInBlock() < o.warningData.getOffsetInBlock();
    }
    return warningData.getBlockIdx() < o.warningData.getBlockIdx();
}

} // namespace processor
} // namespace lbug
