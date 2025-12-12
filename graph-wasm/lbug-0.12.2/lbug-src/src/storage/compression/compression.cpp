#include "storage/compression/compression.h"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <string>

#include "common/assert.h"
#include "common/exception/not_implemented.h"
#include "common/exception/storage.h"
#include "common/null_mask.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/type_utils.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "fastpfor/bitpackinghelpers.h"
#include "storage/compression/bitpacking_int128.h"
#include "storage/compression/bitpacking_utils.h"
#include "storage/compression/float_compression.h"
#include "storage/compression/sign_extend.h"
#include "storage/storage_utils.h"
#include "storage/table/column_chunk_data.h"
#include <ranges>

using namespace lbug::common;

namespace lbug {
namespace storage {

template<typename T>
auto getTypedMinMax(std::span<const T> data, const NullMask* nullMask, uint64_t nullMaskOffset) {
    std::optional<StorageValue> min, max;
    KU_ASSERT(data.size() > 0);
    if (!nullMask || nullMask->hasNoNullsGuarantee()) {
        auto [minRaw, maxRaw] = std::minmax_element(data.begin(), data.end());
        min = StorageValue(*minRaw);
        max = StorageValue(*maxRaw);
    } else {
        for (uint64_t i = 0; i < data.size(); i++) {
            if (!nullMask->isNull(nullMaskOffset + i)) {
                if (!min || data[i] < min->get<T>()) {
                    min = StorageValue(data[i]);
                }
                if (!max || data[i] > max->get<T>()) {
                    max = StorageValue(data[i]);
                }
            }
        }
    }
    return std::make_pair(min, max);
}

uint32_t getDataTypeSizeInChunk(const common::LogicalType& dataType) {
    return getDataTypeSizeInChunk(dataType.getPhysicalType());
}

uint32_t getDataTypeSizeInChunk(const common::PhysicalTypeID& dataType) {
    switch (dataType) {
    case PhysicalTypeID::STRING:
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        return 0;
    }
    case PhysicalTypeID::INTERNAL_ID: {
        return sizeof(offset_t);
    }
    default: {
        auto size = PhysicalTypeUtils::getFixedTypeSize(dataType);
        KU_ASSERT(size <= LBUG_PAGE_SIZE);
        return size;
    }
    }
}

ALPMetadata::ALPMetadata(const alp::state& alpState, common::PhysicalTypeID physicalType)
    : exp(alpState.exp), fac(alpState.fac), exceptionCount(alpState.exceptions_count) {
    const size_t physicalTypeSize = PhysicalTypeUtils::getFixedTypeSize(physicalType);

    // to get the exception capacity we find the number of bytes needed to store the current
    // exception count, take the smallest power of 2 greater than or equal to that value
    // or the size of one page (whichever is larger)
    // then find how many exceptions fit in that size
    exceptionCapacity =
        static_cast<uint64_t>(std::bit_ceil(alpState.exceptions_count * physicalTypeSize)) /
        physicalTypeSize;
}

void ALPMetadata::serialize(common::Serializer& serializer) const {
    serializer.write(exp);
    serializer.write(fac);
    serializer.write(exceptionCount);
    serializer.write(exceptionCapacity);
}

ALPMetadata ALPMetadata::deserialize(common::Deserializer& deserializer) {
    ALPMetadata ret;
    deserializer.deserializeValue(ret.exp);
    deserializer.deserializeValue(ret.fac);
    deserializer.deserializeValue(ret.exceptionCount);
    deserializer.deserializeValue(ret.exceptionCapacity);
    return ret;
}

std::unique_ptr<ExtraMetadata> ALPMetadata::copy() {
    return std::make_unique<ALPMetadata>(*this);
}

CompressionMetadata::CompressionMetadata(StorageValue min, StorageValue max,
    CompressionType compression, const alp::state& state, StorageValue minEncoded,
    StorageValue maxEncoded, common::PhysicalTypeID physicalType)
    : min(min), max(max), compression(compression),
      extraMetadata(std::make_unique<ALPMetadata>(state, physicalType)) {
    if (compression == CompressionType::ALP) {
        children.emplace_back(minEncoded, maxEncoded,
            minEncoded == maxEncoded ? CompressionType::CONSTANT :
                                       CompressionType::INTEGER_BITPACKING);
    }
}

const CompressionMetadata& CompressionMetadata::getChild(offset_t idx) const {
    KU_ASSERT(idx < getChildCount(compression));
    return children[idx];
}

CompressionMetadata::CompressionMetadata(const CompressionMetadata& o)
    : min{o.min}, max{o.max}, compression{o.compression}, children{o.children} {
    if (o.extraMetadata.has_value()) {
        this->extraMetadata = o.extraMetadata.value()->copy();
    }
}

CompressionMetadata& CompressionMetadata::operator=(const CompressionMetadata& o) {
    if (this != &o) {
        min = o.min;
        max = o.max;
        compression = o.compression;
        if (o.extraMetadata.has_value()) {
            extraMetadata = o.extraMetadata.value()->copy();
        } else {
            extraMetadata = {};
        }
        children = o.children;
    }
    return *this;
}

void CompressionMetadata::serialize(Serializer& serializer) const {
    serializer.write(min);
    serializer.write(max);
    serializer.write(compression);

    if (compression == CompressionType::ALP) {
        floatMetadata()->serialize(serializer);
    }

    KU_ASSERT(children.size() == getChildCount(compression));
    for (size_t i = 0; i < children.size(); ++i) {
        children[i].serialize(serializer);
    }
}

CompressionMetadata CompressionMetadata::deserialize(common::Deserializer& deserializer) {
    StorageValue min{};
    StorageValue max{};
    CompressionType compressionType{};
    deserializer.deserializeValue(min);
    deserializer.deserializeValue(max);
    deserializer.deserializeValue(compressionType);

    CompressionMetadata ret(min, max, compressionType);

    if (compressionType == CompressionType::ALP) {
        auto alpMetadata = std::make_unique<ALPMetadata>(ALPMetadata::deserialize(deserializer));
        ret.extraMetadata = std::move(alpMetadata);
    }

    for (size_t i = 0; i < getChildCount(compressionType); ++i) {
        ret.children.push_back(deserialize(deserializer));
    }

    return ret;
}

bool CompressionMetadata::canAlwaysUpdateInPlace() const {
    switch (compression) {
    case CompressionType::BOOLEAN_BITPACKING:
    case CompressionType::UNCOMPRESSED: {
        return true;
    }
    case CompressionType::CONSTANT:
    case CompressionType::ALP:
    case CompressionType::INTEGER_BITPACKING: {
        return false;
    }
    default: {
        throw common::StorageException(
            "Unknown compression type with ID " + std::to_string((uint8_t)compression));
    }
    }
}

bool CompressionMetadata::canUpdateInPlace(const uint8_t* data, uint32_t pos, uint64_t numValues,
    PhysicalTypeID physicalType, InPlaceUpdateLocalState& localUpdateState,
    const std::optional<NullMask>& nullMask) const {
    if (canAlwaysUpdateInPlace()) {
        return true;
    }
    switch (compression) {
    case CompressionType::CONSTANT: {
        // Value can be updated in place only if it is identical to the value already stored.
        switch (physicalType) {
        case PhysicalTypeID::BOOL: {
            for (uint64_t i = pos; i < pos + numValues; i++) {
                if (nullMask && nullMask->isNull(i)) {
                    continue;
                }
                if (NullMask::isNull(reinterpret_cast<const uint64_t*>(data), i) !=
                    static_cast<bool>(min.unsignedInt)) {
                    return false;
                }
            }
            return true;
        }
        default: {
            for (uint64_t i = pos; i < pos + numValues; i++) {
                if (nullMask && nullMask->isNull(i)) {
                    continue;
                }
                auto size = getDataTypeSizeInChunk(physicalType);
                if (memcmp(data + i * size, &min.unsignedInt, size) != 0) {
                    return false;
                }
            }
            return true;
        }
        }
    }
    case CompressionType::BOOLEAN_BITPACKING:
    case CompressionType::UNCOMPRESSED: {
        return true;
    }
    case CompressionType::ALP: {
        return TypeUtils::visit(
            physicalType,
            [&]<std::floating_point T>(T) {
                auto values = std::span<const T>(reinterpret_cast<const T*>(data) + pos, numValues);
                return FloatCompression<T>::canUpdateInPlace(values, *this, localUpdateState,
                    std::move(nullMask), pos);
            },
            [&](auto) {
                throw common::StorageException("Attempted to read from a column chunk which "
                                               "uses float compression but does "
                                               "not have a supported physical type: " +
                                               PhysicalTypeUtils::toString(physicalType));
                return false;
            });
    }
    case CompressionType::INTEGER_BITPACKING: {
        auto cdata = const_cast<uint8_t*>(data);
        return TypeUtils::visit(
            physicalType,
            [&]<IntegerBitpackingType T>(T) {
                auto values = std::span<T>(reinterpret_cast<T*>(cdata) + pos, numValues);
                return IntegerBitpacking<T>::canUpdateInPlace(values, *this, std::move(nullMask),
                    pos);
            },
            [&](internalID_t) {
                auto values =
                    std::span<uint64_t>(reinterpret_cast<uint64_t*>(cdata) + pos, numValues);
                return IntegerBitpacking<uint64_t>::canUpdateInPlace(values, *this,
                    std::move(nullMask), pos);
            },
            [&](auto) {
                throw common::StorageException("Attempted to read from a column chunk which "
                                               "uses integer bitpacking but does "
                                               "not have a supported integer physical type: " +
                                               PhysicalTypeUtils::toString(physicalType));
                return false;
            });
    }
    default: {
        throw common::StorageException(
            "Unknown compression type with ID " + std::to_string((uint8_t)compression));
    }
    }
}

uint64_t CompressionMetadata::numValues(uint64_t pageSize, const LogicalType& dataType) const {
    return numValues(pageSize, dataType.getPhysicalType());
}

uint64_t CompressionMetadata::numValues(uint64_t pageSize, common::PhysicalTypeID dataType) const {
    switch (compression) {
    case CompressionType::CONSTANT: {
        return std::numeric_limits<uint64_t>::max();
    }
    case CompressionType::UNCOMPRESSED: {
        return Uncompressed::numValues(pageSize, dataType);
    }
    case CompressionType::INTEGER_BITPACKING: {
        switch (dataType) {
        case PhysicalTypeID::INT128:
            return IntegerBitpacking<int128_t>::numValues(pageSize, *this);
        case PhysicalTypeID::INT64:
            return IntegerBitpacking<int64_t>::numValues(pageSize, *this);
        case PhysicalTypeID::INT32:
            return IntegerBitpacking<int32_t>::numValues(pageSize, *this);
        case PhysicalTypeID::INT16:
            return IntegerBitpacking<int16_t>::numValues(pageSize, *this);
        case PhysicalTypeID::INT8:
            return IntegerBitpacking<int8_t>::numValues(pageSize, *this);
        case PhysicalTypeID::INTERNAL_ID:
        case PhysicalTypeID::UINT64:
            return IntegerBitpacking<uint64_t>::numValues(pageSize, *this);
        case PhysicalTypeID::UINT32:
            return IntegerBitpacking<uint32_t>::numValues(pageSize, *this);
        case PhysicalTypeID::UINT16:
            return IntegerBitpacking<uint16_t>::numValues(pageSize, *this);
        case PhysicalTypeID::UINT8:
            return IntegerBitpacking<uint8_t>::numValues(pageSize, *this);
        default: {
            throw common::StorageException(
                "Attempted to read from a column chunk which uses integer bitpacking but does "
                "not "
                "have a supported integer physical type: " +
                PhysicalTypeUtils::toString(dataType));
        }
        }
    }
    case CompressionType::ALP: {
        switch (dataType) {
        case PhysicalTypeID::DOUBLE: {
            return FloatCompression<double>::numValues(pageSize, *this);
        }
        case PhysicalTypeID::FLOAT: {
            return FloatCompression<float>::numValues(pageSize, *this);
        }
        default: {
            throw common::StorageException(
                "Attempted to read from a column chunk which uses float compression but does "
                "not "
                "have a supported physical type: " +
                PhysicalTypeUtils::toString(dataType));
        }
        }
    }
    case CompressionType::BOOLEAN_BITPACKING: {
        return BooleanBitpacking::numValues(pageSize);
    }
    default: {
        throw common::StorageException(
            "Unknown compression type with ID " + std::to_string((uint8_t)compression));
    }
    }
}

size_t CompressionMetadata::getChildCount(CompressionType compressionType) {
    switch (compressionType) {
    case CompressionType::ALP: {
        return 1;
    }
    default: {
        return 0;
    }
    }
}

std::optional<CompressionMetadata> ConstantCompression::analyze(const ColumnChunkData& chunk) {
    switch (chunk.getDataType().getPhysicalType()) {
    // Only values that can fit in the CompressionMetadata's data field can use constant
    // compression
    case PhysicalTypeID::BOOL: {
        if (chunk.getCapacity() == 0) {
            return std::optional(
                CompressionMetadata(StorageValue(0), StorageValue(0), CompressionType::CONSTANT));
        }
        auto firstValue = chunk.getValue<bool>(0);

        // TODO(bmwinger): This could be optimized. We could do bytewise comparison with memcmp,
        // but we need to make sure to stop at the end of the values to avoid false positives
        for (auto i = 1u; i < chunk.getNumValues(); i++) {
            // If any value is different from the first one, we can't use constant compression
            if (firstValue != chunk.getValue<bool>(i)) {
                return std::nullopt;
            }
        }
        auto value = StorageValue(firstValue);
        return std::optional(CompressionMetadata(value, value, CompressionType::CONSTANT));
    }
    case PhysicalTypeID::INTERNAL_ID:
    case PhysicalTypeID::DOUBLE:
    case PhysicalTypeID::FLOAT:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT128: {
        uint8_t size = chunk.getNumBytesPerValue();
        StorageValue value{};
        KU_ASSERT(size <= sizeof(value.unsignedInt));
        // If there are no values, or only one value, we will always use constant compression
        // since the loop won't execute
        for (auto i = 1u; i < chunk.getNumValues(); i++) {
            // If any value is different from the first one, we can't use constant compression
            if (std::memcmp(chunk.getData(), chunk.getData() + i * size, size) != 0) {
                return std::nullopt;
            }
        }
        if (chunk.getNumValues() > 0) {
            std::memcpy(&value.unsignedInt, chunk.getData(), size);
        }
        return std::optional(CompressionMetadata(value, value, CompressionType::CONSTANT));
    }
    default: {
        return std::optional<CompressionMetadata>();
    }
    }
}

uint64_t Uncompressed::numValues(uint64_t dataSize, common::PhysicalTypeID physicalType) {
    uint32_t numBytesPerValue = getDataTypeSizeInChunk(physicalType);
    return numBytesPerValue == 0 ? UINT64_MAX : dataSize / numBytesPerValue;
}

uint64_t Uncompressed::numValues(uint64_t dataSize, const common::LogicalType& logicalType) {
    return numValues(dataSize, logicalType.getPhysicalType());
}

std::string CompressionMetadata::toString(const PhysicalTypeID physicalType) const {
    switch (compression) {
    case CompressionType::UNCOMPRESSED: {
        return "UNCOMPRESSED";
    }
    case CompressionType::ALP: {
        uint8_t bitWidth = TypeUtils::visit(
            physicalType,
            [&]<std::floating_point T>(T) {
                static constexpr common::idx_t BITPACKING_CHILD_IDX = 0;
                return IntegerBitpacking<typename FloatCompression<T>::EncodedType>::getPackingInfo(
                    getChild(BITPACKING_CHILD_IDX))
                    .bitWidth;
            },
            [](auto) -> uint8_t { KU_UNREACHABLE; });
        return stringFormat("FLOAT_COMPRESSION[{}], {} Exceptions", bitWidth,
            floatMetadata()->exceptionCount);
    }
    case CompressionType::INTEGER_BITPACKING: {
        uint8_t bitWidth = TypeUtils::visit(
            physicalType,
            [&](common::internalID_t) {
                return IntegerBitpacking<uint64_t>::getPackingInfo(*this).bitWidth;
            },
            [](bool) -> uint8_t { KU_UNREACHABLE; },
            [&]<numeric_utils::IsIntegral T>(
                T) { return IntegerBitpacking<T>::getPackingInfo(*this).bitWidth; },
            [](auto) -> uint8_t { KU_UNREACHABLE; });
        return stringFormat("INTEGER_BITPACKING[{}]", bitWidth);
    }
    case CompressionType::BOOLEAN_BITPACKING: {
        return "BOOLEAN_BITPACKING";
    }
    case CompressionType::CONSTANT: {
        return "CONSTANT";
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

void ConstantCompression::decompressValues(uint8_t* dstBuffer, uint64_t dstOffset,
    uint64_t numValues, common::PhysicalTypeID physicalType, uint32_t numBytesPerValue,
    const CompressionMetadata& metadata) {
    auto start = dstBuffer + dstOffset * numBytesPerValue;
    auto end = dstBuffer + (dstOffset + numValues) * numBytesPerValue;

    TypeUtils::visit(
        physicalType,
        [&](common::internalID_t) {
            std::fill(reinterpret_cast<uint64_t*>(start), reinterpret_cast<uint64_t*>(end),
                metadata.min.get<uint64_t>());
        },
        [&]<typename T>
            requires(numeric_utils::IsIntegral<T> || std::floating_point<T>)
        (T) {
            std::fill(reinterpret_cast<T*>(start), reinterpret_cast<T*>(end),
                metadata.min.get<T>());
        },
        [&](auto) {
            throw NotImplementedException("CONSTANT compression is not implemented for type " +
                                          PhysicalTypeUtils::toString(physicalType));
        });
}

void ConstantCompression::decompressFromPage(const uint8_t* /*srcBuffer*/, uint64_t /*srcOffset*/,
    uint8_t* dstBuffer, uint64_t dstOffset, uint64_t numValues,
    const CompressionMetadata& metadata) const {
    return decompressValues(dstBuffer, dstOffset, numValues, dataType, numBytesPerValue, metadata);
}

void ConstantCompression::copyFromPage(const uint8_t* srcBuffer, uint64_t srcOffset,
    uint8_t* dstBuffer, uint64_t dstOffset, uint64_t numValues,
    const CompressionMetadata& metadata) const {
    if (dataType == common::PhysicalTypeID::BOOL) {
        common::NullMask::setNullRange(reinterpret_cast<uint64_t*>(dstBuffer), dstOffset, numValues,
            metadata.min.unsignedInt);
    } else {
        decompressFromPage(srcBuffer, srcOffset, dstBuffer, dstOffset, numValues, metadata);
    }
}

template<typename T>
inline T abs(T value);

template<typename T>
    requires std::is_unsigned_v<T>
inline T abs(T value) {
    return value;
}

template<typename T>
    requires std::is_signed_v<T>
inline T abs(T value) {
    return std::abs(value);
}

template<>
inline int128_t abs<int128_t>(int128_t value) {
    return value >= 0 ? value : -value;
}

template<IntegerBitpackingType T>
BitpackInfo<T> IntegerBitpacking<T>::getPackingInfo(const CompressionMetadata& metadata) {
    auto max = metadata.max.get<T>();
    auto min = metadata.min.get<T>();
    bool hasNegative = false;
    T offset = 0;
    uint8_t bitWidth = 0;
    // Frame of reference encoding is only used when values are either all positive or all
    // negative, and when we will save at least 1 bit per value. when the chunk was first
    // compressed
    if (min > 0 && max > 0 &&
        numeric_utils::bitWidth((U)(max - min)) < numeric_utils::bitWidth((U)max)) {
        offset = min;
        bitWidth = static_cast<uint8_t>(numeric_utils::bitWidth((U)(max - min)));
        hasNegative = false;
    } else if (min < 0 && max < 0 &&
               numeric_utils::bitWidth((U)(min - max)) < numeric_utils::bitWidth((U)max)) {
        offset = (U)max;
        bitWidth = static_cast<uint8_t>(numeric_utils::bitWidth((U)(min - max))) + 1;
        // This is somewhat suboptimal since we know that the values are all negative
        // We could use an offset equal to the minimum, but values which are all negative are
        // probably going to grow in the negative direction, leading to many re-compressions
        // when inserting
        hasNegative = true;
    } else if (min < 0) {
        bitWidth =
            static_cast<uint8_t>(numeric_utils::bitWidth((U)std::max(abs<T>(min), abs<T>(max)))) +
            1;
        hasNegative = true;
    } else {
        bitWidth =
            static_cast<uint8_t>(numeric_utils::bitWidth((U)std::max(abs<T>(min), abs<T>(max))));
        hasNegative = false;
    }
    return BitpackInfo<T>{bitWidth, hasNegative, offset};
}

template<IntegerBitpackingType T>
bool IntegerBitpacking<T>::canUpdateInPlace(std::span<const T> values,
    const CompressionMetadata& metadata, const std::optional<common::NullMask>& nullMask,
    uint64_t nullMaskOffset) {
    auto info = getPackingInfo(metadata);
    auto [min, max] = getTypedMinMax<T>(values, nullMask ? &*nullMask : nullptr, nullMaskOffset);
    KU_ASSERT((min && max) || (!min && !max));
    // If all values are null update can trivially be done in-place
    if (!min) {
        return true;
    }
    auto newMetadata =
        CompressionMetadata(StorageValue(std::min(metadata.min.get<T>(), min->template get<T>())),
            StorageValue(std::max(metadata.max.get<T>(), max->template get<T>())),
            metadata.compression);
    auto newInfo = getPackingInfo(newMetadata);

    if (info.bitWidth != newInfo.bitWidth || info.hasNegative != newInfo.hasNegative ||
        info.offset != newInfo.offset) {
        return false;
    }
    return true;
}

template<typename T>
void fastunpack(const uint8_t* in, T* out, uint32_t bitWidth) {
    if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int32_t> ||
                  std::is_same_v<numeric_utils::MakeSignedT<T>, int64_t>) {
        FastPForLib::fastunpack((const uint32_t*)in, out, bitWidth);
    } else if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int16_t>) {
        FastPForLib::fastunpack((const uint16_t*)in, out, bitWidth);
    } else if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int8_t>) {
        FastPForLib::fastunpack((const uint8_t*)in, out, bitWidth);
    } else {
        static_assert(std::is_same_v<numeric_utils::MakeSignedT<T>, int128_t>);
        Int128Packer::unpack(reinterpret_cast<const uint32_t*>(in), out, bitWidth);
    }
}

template<typename T>
void fastpack(const T* in, uint8_t* out, uint8_t bitWidth) {
    if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int32_t> ||
                  std::is_same_v<numeric_utils::MakeSignedT<T>, int64_t>) {
        FastPForLib::fastpack(in, (uint32_t*)out, bitWidth);
    } else if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int16_t>) {
        FastPForLib::fastpack(in, (uint16_t*)out, bitWidth);
    } else if constexpr (std::is_same_v<numeric_utils::MakeSignedT<T>, int8_t>) {
        FastPForLib::fastpack(in, (uint8_t*)out, bitWidth);
    } else {
        static_assert(std::is_same_v<numeric_utils::MakeSignedT<T>, int128_t>);
        Int128Packer::pack(in, reinterpret_cast<uint32_t*>(out), bitWidth);
    }
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::setPartialChunkInPlace(const uint8_t* srcBuffer, offset_t posInSrc,
    uint8_t* dstBuffer, offset_t posInDst, offset_t numValues, const BitpackInfo<T>& header) const {
    U tmpChunk[CHUNK_SIZE];
    copyValuesToTempChunkWithOffset(reinterpret_cast<const U*>(srcBuffer) + posInSrc, tmpChunk,
        header, numValues);
    packPartialChunk(tmpChunk, dstBuffer, posInDst, header, numValues);
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::setValuesFromUncompressed(const uint8_t* srcBuffer, offset_t posInSrc,
    uint8_t* dstBuffer, offset_t posInDst, offset_t numValues, const CompressionMetadata& metadata,
    const NullMask* nullMask) const {
    KU_UNUSED(nullMask);

    auto header = getPackingInfo(metadata);

    // Null values will usually be 0, which will not be able to be stored if there is a
    // non-zero offset However we don't care about the value stored for null values
    // Currently they will be mangled by storage+recovery (underflow in the subtraction
    // below)
    KU_ASSERT(numValues == static_cast<offset_t>(std::ranges::count_if(
                               std::ranges::iota_view{posInSrc, posInSrc + numValues},
                               [srcBuffer, &metadata, nullMask](offset_t i) {
                                   auto value = reinterpret_cast<const T*>(srcBuffer)[i];
                                   return (nullMask && nullMask->isNull(i)) ||
                                          canUpdateInPlace(std::span(&value, 1), metadata);
                               })));

    // Data can be considered to be stored in aligned chunks of 32 values
    // with a size of 32 * bitWidth bits,
    // or bitWidth 32-bit values (we cast the buffer to a uint32_t* later).

    // update unaligned values in the first chunk
    auto valuesInFirstChunk = std::min(CHUNK_SIZE - (posInDst % CHUNK_SIZE), numValues);
    offset_t dstIndex = posInDst;
    if (valuesInFirstChunk < CHUNK_SIZE) {
        // update unaligned values in the last chunk
        setPartialChunkInPlace(srcBuffer, posInSrc, dstBuffer, posInDst, valuesInFirstChunk,
            header);
        dstIndex += valuesInFirstChunk;
    }

    // update chunk-aligned values using fastpack/unpack
    for (; dstIndex + CHUNK_SIZE <= posInDst + numValues; dstIndex += CHUNK_SIZE) {
        U chunk[CHUNK_SIZE];

        const size_t chunkIndexOffsetInSrc = posInSrc + dstIndex - posInDst;
        copyValuesToTempChunkWithOffset(reinterpret_cast<const U*>(srcBuffer) +
                                            chunkIndexOffsetInSrc,
            chunk, header, CHUNK_SIZE);

        const offset_t dstOffsetBytes = dstIndex * header.bitWidth / 8;
        fastpack(chunk, dstBuffer + dstOffsetBytes, header.bitWidth);
    }

    // update unaligned values in the last chunk
    const auto lastChunkIndexOffset = dstIndex - posInDst;
    const size_t unalignedValuesToPack = numValues - lastChunkIndexOffset;
    if (unalignedValuesToPack > 0) {
        setPartialChunkInPlace(srcBuffer, posInSrc + lastChunkIndexOffset, dstBuffer,
            posInDst + lastChunkIndexOffset, unalignedValuesToPack, header);
    }
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::getValues(const uint8_t* chunkStart, uint8_t pos, uint8_t* dst,
    uint8_t numValuesToRead, const BitpackInfo<T>& header) const {
    const size_t maxReadIndex = pos + numValuesToRead;
    KU_ASSERT(maxReadIndex <= CHUNK_SIZE);

    for (size_t i = pos; i < maxReadIndex; i++) {
        // Always use unsigned version of unpacker to prevent sign-bit filling when right
        // shifting
        U& out = reinterpret_cast<U*>(dst)[i - pos];
        BitpackingUtils<U>::unpackSingle(chunkStart, &out, header.bitWidth, i);

        if (header.hasNegative && header.bitWidth > 0) {
            SignExtend<T, U, 1>((uint8_t*)&out, header.bitWidth);
        }

        if (header.offset != 0) {
            reinterpret_cast<T&>(out) += header.offset;
        }
    }
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::packPartialChunk(const U* srcBuffer, uint8_t* dstBuffer, size_t posInDst,
    BitpackInfo<T> info, size_t numValuesToPack) const {
    for (size_t i = 0; i < numValuesToPack; ++i) {
        BitpackingUtils<U>::packSingle(srcBuffer[i], dstBuffer, info.bitWidth, i + posInDst);
    }
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::copyValuesToTempChunkWithOffset(const U* srcBuffer, U* tmpBuffer,
    BitpackInfo<T> info, size_t numValuesToCopy) const {
    for (auto j = 0u; j < numValuesToCopy; j++) {
        tmpBuffer[j] = static_cast<U>((T)(srcBuffer[j]) - info.offset);
    }
}

template<IntegerBitpackingType T>
uint64_t IntegerBitpacking<T>::compressNextPage(const uint8_t*& srcBuffer,
    uint64_t numValuesRemaining, uint8_t* dstBuffer, uint64_t dstBufferSize,
    const CompressionMetadata& metadata) const {
    // TODO(bmwinger): this is hacky; we need a better system for dynamically choosing between
    // algorithms when compressing
    if (metadata.compression == CompressionType::UNCOMPRESSED) {
        return Uncompressed(sizeof(T)).compressNextPage(srcBuffer, numValuesRemaining, dstBuffer,
            dstBufferSize, metadata);
    }
    KU_ASSERT(metadata.compression == CompressionType::INTEGER_BITPACKING);
    auto info = getPackingInfo(metadata);
    auto bitWidth = info.bitWidth;

    if (bitWidth == 0) {
        return 0;
    }
    auto numValuesToCompress = std::min(numValuesRemaining, numValues(dstBufferSize, info));
    // Round up to nearest byte
    auto sizeToCompress =
        numValuesToCompress * bitWidth / 8 + (numValuesToCompress * bitWidth % 8 != 0);
    KU_ASSERT(dstBufferSize >= CHUNK_SIZE);
    KU_ASSERT(dstBufferSize >= sizeToCompress);
    // This might overflow the source buffer if there are fewer values remaining than the chunk
    // size so we stop at the end of the last full chunk and use a temporary array to avoid
    // overflow.
    if (info.offset == 0) {
        auto lastFullChunkEnd = numValuesToCompress - numValuesToCompress % CHUNK_SIZE;
        for (auto i = 0ull; i < lastFullChunkEnd; i += CHUNK_SIZE) {
            fastpack(reinterpret_cast<const U*>(srcBuffer) + i, dstBuffer + i * bitWidth / 8,
                bitWidth);
        }
        // Pack last partial chunk, avoiding overflows
        const size_t remainingNumValues = numValuesToCompress % CHUNK_SIZE;
        if (remainingNumValues > 0) {
            packPartialChunk(reinterpret_cast<const U*>(srcBuffer) + lastFullChunkEnd,
                dstBuffer + lastFullChunkEnd * bitWidth / 8, 0, info, remainingNumValues);
        }
    } else {
        U tmp[CHUNK_SIZE];
        auto lastFullChunkEnd = numValuesToCompress - numValuesToCompress % CHUNK_SIZE;
        for (auto i = 0ull; i < lastFullChunkEnd; i += CHUNK_SIZE) {
            copyValuesToTempChunkWithOffset(reinterpret_cast<const U*>(srcBuffer) + i, tmp, info,
                CHUNK_SIZE);
            fastpack(tmp, dstBuffer + i * bitWidth / 8, bitWidth);
        }
        // Pack last partial chunk, avoiding overflows
        auto remainingValues = numValuesToCompress % CHUNK_SIZE;
        if (remainingValues > 0) {
            copyValuesToTempChunkWithOffset(reinterpret_cast<const U*>(srcBuffer) +
                                                lastFullChunkEnd,
                tmp, info, remainingValues);
            packPartialChunk(tmp, dstBuffer + lastFullChunkEnd * bitWidth / 8, 0, info,
                remainingValues);
        }
    }
    srcBuffer += numValuesToCompress * sizeof(U);
    return sizeToCompress;
}

template<IntegerBitpackingType T>
void IntegerBitpacking<T>::decompressFromPage(const uint8_t* srcBuffer, uint64_t srcOffset,
    uint8_t* dstBuffer, uint64_t dstOffset, uint64_t numValues,
    const CompressionMetadata& metadata) const {
    auto info = getPackingInfo(metadata);

    auto srcCursor = getChunkStart(srcBuffer, srcOffset, info.bitWidth);
    auto valuesInFirstChunk = std::min(CHUNK_SIZE - (srcOffset % CHUNK_SIZE), numValues);
    auto bytesPerChunk = CHUNK_SIZE / 8 * info.bitWidth;
    auto dstIndex = dstOffset;

    // Copy values which aren't aligned to the start of the chunk
    if (valuesInFirstChunk < CHUNK_SIZE) {
        getValues(srcCursor, srcOffset % CHUNK_SIZE, dstBuffer + dstIndex * sizeof(U),
            valuesInFirstChunk, info);
        if (numValues == valuesInFirstChunk) {
            return;
        }
        // Start at the end of the first partial chunk
        srcCursor += bytesPerChunk;
        dstIndex += valuesInFirstChunk;
    }

    // Use fastunpack to directly unpack the full-sized chunks
    for (; dstIndex + CHUNK_SIZE <= dstOffset + numValues; dstIndex += CHUNK_SIZE) {
        fastunpack(srcCursor, (U*)dstBuffer + dstIndex, info.bitWidth);
        if (info.hasNegative && info.bitWidth > 0) {
            SignExtend<T, U, CHUNK_SIZE>(dstBuffer + dstIndex * sizeof(U), info.bitWidth);
        }
        if (info.offset != 0) {
            for (auto i = 0u; i < CHUNK_SIZE; i++) {
                ((T*)dstBuffer)[dstIndex + i] += info.offset;
            }
        }
        srcCursor += bytesPerChunk;
    }
    // Copy remaining values from within the last chunk.
    if (dstIndex < dstOffset + numValues) {
        getValues(srcCursor, 0, dstBuffer + dstIndex * sizeof(U), dstOffset + numValues - dstIndex,
            info);
    }
}

template class IntegerBitpacking<int8_t>;
template class IntegerBitpacking<int16_t>;
template class IntegerBitpacking<int32_t>;
template class IntegerBitpacking<int64_t>;
template class IntegerBitpacking<int128_t>;
template class IntegerBitpacking<uint8_t>;
template class IntegerBitpacking<uint16_t>;
template class IntegerBitpacking<uint32_t>;
template class IntegerBitpacking<uint64_t>;

void BooleanBitpacking::setValuesFromUncompressed(const uint8_t* srcBuffer, offset_t srcOffset,
    uint8_t* dstBuffer, offset_t dstOffset, offset_t numValues,
    const CompressionMetadata& /*metadata*/, const NullMask* /*nullMask*/) const {
    for (auto i = 0u; i < numValues; i++) {
        NullMask::setNull((uint64_t*)dstBuffer, dstOffset + i, ((bool*)srcBuffer)[srcOffset + i]);
    }
}

uint64_t BooleanBitpacking::compressNextPage(const uint8_t*& srcBuffer, uint64_t numValuesRemaining,
    uint8_t* dstBuffer, uint64_t dstBufferSize, const CompressionMetadata& /*metadata*/) const {
    // TODO(bmwinger): Optimize, e.g. using an integer bitpacking function
    auto numValuesToCompress = std::min(numValuesRemaining, numValues(dstBufferSize));
    for (auto i = 0ull; i < numValuesToCompress; i++) {
        NullMask::setNull((uint64_t*)dstBuffer, i, srcBuffer[i]);
    }
    srcBuffer += numValuesToCompress / 8;
    // Will be a multiple of 8 except for the last iteration
    return numValuesToCompress / 8 + (bool)(numValuesToCompress % 8);
}

void BooleanBitpacking::decompressFromPage(const uint8_t* srcBuffer, uint64_t srcOffset,
    uint8_t* dstBuffer, uint64_t dstOffset, uint64_t numValues,
    const CompressionMetadata& /*metadata*/) const {
    // TODO(bmwinger): Optimize, e.g. using an integer bitpacking function
    for (auto i = 0ull; i < numValues; i++) {
        ((bool*)dstBuffer)[dstOffset + i] = NullMask::isNull((uint64_t*)srcBuffer, srcOffset + i);
    }
}

void BooleanBitpacking::copyFromPage(const uint8_t* srcBuffer, uint64_t srcOffset,
    uint8_t* dstBuffer, uint64_t dstOffset, uint64_t numValues,
    const CompressionMetadata& /*metadata*/) const {
    NullMask::copyNullMask(reinterpret_cast<const uint64_t*>(srcBuffer), srcOffset,
        reinterpret_cast<uint64_t*>(dstBuffer), dstOffset, numValues);
}

void ReadCompressedValuesFromPageToVector::operator()(const uint8_t* frame, PageCursor& pageCursor,
    common::ValueVector* resultVector, uint32_t posInVector, uint64_t numValuesToRead,
    const CompressionMetadata& metadata) {
    switch (metadata.compression) {
    case CompressionType::CONSTANT:
        return constant.decompressFromPage(frame, pageCursor.elemPosInPage, resultVector->getData(),
            posInVector, numValuesToRead, metadata);
    case CompressionType::UNCOMPRESSED:
        return uncompressed.decompressFromPage(frame, pageCursor.elemPosInPage,
            resultVector->getData(), posInVector, numValuesToRead, metadata);
    case CompressionType::ALP: {
        switch (physicalType) {
        case PhysicalTypeID::DOUBLE: {
            return FloatCompression<double>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::FLOAT: {
            return FloatCompression<float>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        default: {
            throw NotImplementedException("Float Compression is not implemented for type " +
                                          PhysicalTypeUtils::toString(physicalType));
        }
        }
    }
    case CompressionType::INTEGER_BITPACKING: {
        switch (physicalType) {
        case PhysicalTypeID::INT128: {
            return IntegerBitpacking<int128_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT64: {
            return IntegerBitpacking<int64_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT32: {
            return IntegerBitpacking<int32_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT16: {
            return IntegerBitpacking<int16_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT8: {
            return IntegerBitpacking<int8_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INTERNAL_ID:
        case PhysicalTypeID::UINT64: {
            return IntegerBitpacking<uint64_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT32: {
            return IntegerBitpacking<uint32_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT16: {
            return IntegerBitpacking<uint16_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT8: {
            return IntegerBitpacking<uint8_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                resultVector->getData(), posInVector, numValuesToRead, metadata);
        }
        default: {
            throw NotImplementedException("INTEGER_BITPACKING is not implemented for type " +
                                          PhysicalTypeUtils::toString(physicalType));
        }
        }
    }
    case CompressionType::BOOLEAN_BITPACKING:
        return booleanBitpacking.decompressFromPage(frame, pageCursor.elemPosInPage,
            resultVector->getData(), posInVector, numValuesToRead, metadata);
    default:
        KU_UNREACHABLE;
    }
}

void ReadCompressedValuesFromPage::operator()(const uint8_t* frame, PageCursor& pageCursor,
    uint8_t* result, uint32_t startPosInResult, uint64_t numValuesToRead,
    const CompressionMetadata& metadata) {
    switch (metadata.compression) {
    case CompressionType::CONSTANT:
        return constant.copyFromPage(frame, pageCursor.elemPosInPage, result, startPosInResult,
            numValuesToRead, metadata);
    case CompressionType::UNCOMPRESSED:
        return uncompressed.decompressFromPage(frame, pageCursor.elemPosInPage, result,
            startPosInResult, numValuesToRead, metadata);
    case CompressionType::ALP: {
        switch (physicalType) {
        case PhysicalTypeID::DOUBLE: {
            return FloatCompression<double>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::FLOAT: {
            return FloatCompression<float>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        default: {
            throw NotImplementedException("Float Compression is not implemented for type " +
                                          PhysicalTypeUtils::toString(physicalType));
        }
        }
    }
    case CompressionType::INTEGER_BITPACKING: {
        switch (physicalType) {
        case PhysicalTypeID::INT128: {
            return IntegerBitpacking<int128_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT64: {
            return IntegerBitpacking<int64_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT32: {
            return IntegerBitpacking<int32_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT16: {
            return IntegerBitpacking<int16_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INT8: {
            return IntegerBitpacking<int8_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::INTERNAL_ID:
        case PhysicalTypeID::UINT64: {
            return IntegerBitpacking<uint64_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT32: {
            return IntegerBitpacking<uint32_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT16: {
            return IntegerBitpacking<uint16_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        case PhysicalTypeID::UINT8: {
            return IntegerBitpacking<int8_t>().decompressFromPage(frame, pageCursor.elemPosInPage,
                result, startPosInResult, numValuesToRead, metadata);
        }
        default: {
            throw NotImplementedException("INTEGER_BITPACKING is not implemented for type " +
                                          PhysicalTypeUtils::toString(physicalType));
        }
        }
    }
    case CompressionType::BOOLEAN_BITPACKING:
        // Reading into ColumnChunks should be done without decompressing for booleans
        return booleanBitpacking.copyFromPage(frame, pageCursor.elemPosInPage, result,
            startPosInResult, numValuesToRead, metadata);
    default:
        KU_UNREACHABLE;
    }
}

void WriteCompressedValuesToPage::operator()(uint8_t* frame, uint16_t posInFrame,
    const uint8_t* data, offset_t dataOffset, offset_t numValues,
    const CompressionMetadata& metadata, const NullMask* nullMask) {
    switch (metadata.compression) {
    case CompressionType::CONSTANT:
        return constant.setValuesFromUncompressed(data, dataOffset, frame, posInFrame, numValues,
            metadata, nullMask);
    case CompressionType::UNCOMPRESSED:
        return uncompressed.setValuesFromUncompressed(data, dataOffset, frame, posInFrame,
            numValues, metadata, nullMask);
    case CompressionType::INTEGER_BITPACKING: {
        return TypeUtils::visit(physicalType, [&]<typename T>(T) {
            if constexpr (std::same_as<T, bool>) {
                throw NotImplementedException(
                    "INTEGER_BITPACKING is not implemented for type bool");
            } else if constexpr (std::same_as<T, internalID_t>) {
                IntegerBitpacking<uint64_t>().setValuesFromUncompressed(data, dataOffset, frame,
                    posInFrame, numValues, metadata, nullMask);
            } else if constexpr (numeric_utils::IsIntegral<T>) {
                return IntegerBitpacking<T>().setValuesFromUncompressed(data, dataOffset, frame,
                    posInFrame, numValues, metadata, nullMask);
            } else {
                throw NotImplementedException("INTEGER_BITPACKING is not implemented for type " +
                                              PhysicalTypeUtils::toString(physicalType));
            }
        });
    }
    case CompressionType::ALP: {
        return TypeUtils::visit(physicalType, [&]<typename T>(T) {
            if constexpr (std::is_floating_point_v<T>) {
                FloatCompression<T>().setValuesFromUncompressed(data, dataOffset, frame, posInFrame,
                    numValues, metadata, nullMask);
            } else {
                throw NotImplementedException("FLOAT_COMPRESSION is not implemented for type " +
                                              PhysicalTypeUtils::toString(physicalType));
            }
        });
    }
    case CompressionType::BOOLEAN_BITPACKING:
        return booleanBitpacking.copyFromPage(data, dataOffset, frame, posInFrame, numValues,
            metadata);

    default:
        KU_UNREACHABLE;
    }
}

void WriteCompressedValuesToPage::operator()(uint8_t* frame, uint16_t posInFrame,
    common::ValueVector* vector, uint32_t posInVector, offset_t numValues,
    const CompressionMetadata& metadata) {
    if (metadata.compression == CompressionType::BOOLEAN_BITPACKING) {
        booleanBitpacking.setValuesFromUncompressed(vector->getData(), posInVector, frame,
            posInFrame, numValues, metadata, &vector->getNullMask());
    } else {
        (*this)(frame, posInFrame, vector->getData(), posInVector, 1, metadata,
            &vector->getNullMask());
    }
}

std::optional<StorageValue> StorageValue::readFromVector(const common::ValueVector& vector,
    common::offset_t posInVector) {
    return TypeUtils::visit(
        vector.dataType.getPhysicalType(),
        // TODO(bmwinger): concept for supported storagevalue types
        [&]<StorageValueType T>(
            T) { return std::make_optional(StorageValue(vector.getValue<T>(posInVector))); },
        [](auto) { return std::optional<StorageValue>(); });
}

bool StorageValue::gt(const StorageValue& other, common::PhysicalTypeID type) const {
    switch (type) {
    case common::PhysicalTypeID::BOOL:
    case common::PhysicalTypeID::LIST:
    case common::PhysicalTypeID::ARRAY:
    case common::PhysicalTypeID::INTERNAL_ID:
    case common::PhysicalTypeID::STRING:
    case common::PhysicalTypeID::UINT64:
    case common::PhysicalTypeID::UINT32:
    case common::PhysicalTypeID::UINT16:
    case common::PhysicalTypeID::UINT8:
        return this->unsignedInt > other.unsignedInt;
    case common::PhysicalTypeID::INT128:
        return this->signedInt128 > other.signedInt128;
    case common::PhysicalTypeID::INT64:
    case common::PhysicalTypeID::INT32:
    case common::PhysicalTypeID::INT16:
    case common::PhysicalTypeID::INT8:
        return this->signedInt > other.signedInt;
    case common::PhysicalTypeID::FLOAT:
    case common::PhysicalTypeID::DOUBLE:
        return this->floatVal > other.floatVal;
    default:
        KU_UNREACHABLE;
    }
}

std::pair<std::optional<StorageValue>, std::optional<StorageValue>> getMinMaxStorageValue(
    const uint8_t* data, uint64_t offset, uint64_t numValues, PhysicalTypeID physicalType,
    const NullMask* nullMask, bool valueRequiredIfUnsupported) {
    std::pair<std::optional<StorageValue>, std::optional<StorageValue>> returnValue;

    TypeUtils::visit(
        physicalType,
        [&](bool) {
            if (numValues > 0) {
                const auto boolData = reinterpret_cast<const uint64_t*>(data);
                if (!nullMask || nullMask->hasNoNullsGuarantee()) {
                    auto [minRaw, maxRaw] = NullMask::getMinMax(boolData, offset, numValues);
                    returnValue = std::make_pair(std::optional(StorageValue(minRaw)),
                        std::optional(StorageValue(maxRaw)));
                } else {
                    std::optional<StorageValue> min, max;
                    for (size_t i = offset; i < offset + numValues; i++) {
                        if (!nullMask || !nullMask->isNull(i)) {
                            auto boolValue = NullMask::isNull(boolData, i);
                            if (!max || boolValue > max->get<bool>()) {
                                max = boolValue;
                            }
                            if (!min || boolValue < min->get<bool>()) {
                                min = boolValue;
                            }
                        }
                    }
                    returnValue = std::make_pair(min, max);
                }
            }
        },
        [&]<typename T>(T)
            requires(numeric_utils::IsIntegral<T> || std::floating_point<T>)
        {
            if (numValues > 0) {
                auto typedData = std::span(reinterpret_cast<const T*>(data) + offset, numValues);
                returnValue = getTypedMinMax(typedData, nullMask ? &*nullMask : nullptr, offset);
            }
        },
        [&]<typename T>(T)
            requires(std::same_as<T, internalID_t>)
        {
            if (numValues > 0) {
                const auto typedData =
                    std::span(reinterpret_cast<const uint64_t*>(data) + offset, numValues);
                returnValue = getTypedMinMax(typedData, nullMask ? &*nullMask : nullptr, offset);
            }
        },
        [&]<typename T>(T)
            requires(std::same_as<T, interval_t> || std::same_as<T, struct_entry_t> ||
                     std::same_as<T, ku_string_t> || std::same_as<T, list_entry_t> ||
                     std::same_as<T, uint128_t>)
        {
            if (valueRequiredIfUnsupported) {
                // For unsupported types on the first copy,
                // they need a non-optional value to distinguish them
                // from supported types where every value is null
                returnValue.first = std::numeric_limits<uint64_t>::min();
                returnValue.second = std::numeric_limits<uint64_t>::max();
            }
        });
    return returnValue;
}
std::pair<std::optional<StorageValue>, std::optional<StorageValue>> getMinMaxStorageValue(
    const ColumnChunkData& data, uint64_t offset, uint64_t numValues, PhysicalTypeID physicalType,
    bool valueRequiredIfUnsupported) {
    auto nullMask = data.getNullMask();
    return getMinMaxStorageValue(data.getData(), offset, numValues, physicalType,
        nullMask ? &*nullMask : nullptr, valueRequiredIfUnsupported);
}

std::pair<std::optional<StorageValue>, std::optional<StorageValue>> getMinMaxStorageValue(
    const ValueVector& data, uint64_t offset, uint64_t numValues, PhysicalTypeID physicalType,
    bool valueRequiredIfUnsupported) {
    std::pair<std::optional<StorageValue>, std::optional<StorageValue>> returnValue;
    auto& nullMask = data.getNullMask();

    TypeUtils::visit(
        physicalType,
        [&]<typename T>(T)
            requires(numeric_utils::IsIntegral<T> || std::floating_point<T>)
        {
            if (numValues > 0) {
                auto typedData =
                    std::span(reinterpret_cast<const T*>(data.getData()) + offset, numValues);
                returnValue = getTypedMinMax(typedData, &nullMask, offset);
            }
        },
        [&]<typename T>(T)
            requires(std::same_as<T, internalID_t>)
        {
            if (numValues > 0) {
                const auto typedData = std::span(
                    reinterpret_cast<const uint64_t*>(data.getData()) + offset, numValues);
                returnValue = getTypedMinMax(typedData, &nullMask, offset);
            }
        },
        [&]<typename T>(T)
            requires(std::same_as<T, interval_t> || std::same_as<T, struct_entry_t> ||
                     std::same_as<T, ku_string_t> || std::same_as<T, list_entry_t> ||
                     std::same_as<T, uint128_t>)
        {
            if (valueRequiredIfUnsupported) {
                // For unsupported types on the first copy,
                // they need a non-optional value to distinguish them
                // from supported types where every value is null
                returnValue.first = std::numeric_limits<uint64_t>::min();
                returnValue.second = std::numeric_limits<uint64_t>::max();
            }
        });
    return returnValue;
}

} // namespace storage
} // namespace lbug
