#include "processor/operator/persistent/reader/parquet/uuid_column_reader.h"

namespace lbug {
namespace processor {

common::ku_uuid_t UUIDValueConversion::ReadParquetUUID(const uint8_t* input) {
    common::ku_uuid_t result{};
    result.value.low = 0;
    uint64_t unsignedUpper = 0;
    for (auto i = 0u; i < sizeof(uint64_t); i++) {
        unsignedUpper <<= 8;
        unsignedUpper += input[i];
    }
    for (auto i = sizeof(uint64_t); i < sizeof(common::ku_uuid_t); i++) {
        result.value.low <<= 8;
        result.value.low += input[i];
    }
    result.value.high = unsignedUpper;
    result.value.high ^= (int64_t(1) << 63);
    return result;
}

common::ku_uuid_t UUIDValueConversion::plainRead(ByteBuffer& bufferData, ColumnReader& /*reader*/) {
    auto uuidLen = sizeof(common::ku_uuid_t);
    bufferData.available(uuidLen);
    auto res = ReadParquetUUID(reinterpret_cast<const uint8_t*>(bufferData.ptr));
    bufferData.inc(uuidLen);
    return res;
}

void UUIDColumnReader::dictionary(const std::shared_ptr<ResizeableBuffer>& dictionaryData,
    uint64_t numEntries) {
    allocateDict(numEntries * sizeof(common::ku_uuid_t));
    auto dictPtr = reinterpret_cast<common::ku_uuid_t*>(this->dict->ptr);
    for (auto i = 0u; i < numEntries; i++) {
        dictPtr[i] = UUIDValueConversion::plainRead(*dictionaryData, *this);
    }
}

} // namespace processor
} // namespace lbug
