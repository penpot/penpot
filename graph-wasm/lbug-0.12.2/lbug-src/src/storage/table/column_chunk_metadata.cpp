#include "storage/table/column_chunk_metadata.h"

#include "alp/decode.hpp"
#include "alp/encode.hpp"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/system_config.h"
#include "common/type_utils.h"
#include "common/types/types.h"
#include "common/utils.h"
#include "storage/compression/compression.h"
#include "storage/compression/float_compression.h"

namespace lbug::storage {
using namespace common;

ColumnChunkMetadata GetCompressionMetadata::operator()(std::span<const uint8_t> buffer,
    uint64_t numValues, StorageValue min, StorageValue max) const {
    if (min == max) {
        return ColumnChunkMetadata(INVALID_PAGE_IDX, 0, numValues,
            CompressionMetadata(min, max, CompressionType::CONSTANT));
    }
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        return booleanGetMetadata(numValues, min, max);
    }
    case PhysicalTypeID::STRING:
    case PhysicalTypeID::INT64:
    case PhysicalTypeID::INT32:
    case PhysicalTypeID::INT16:
    case PhysicalTypeID::INT8:
    case PhysicalTypeID::INTERNAL_ID:
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::UINT64:
    case PhysicalTypeID::UINT32:
    case PhysicalTypeID::UINT16:
    case PhysicalTypeID::UINT8:
    case PhysicalTypeID::INT128: {
        return GetBitpackingMetadata(alg, dataType)(buffer, numValues, min, max);
    }
    case PhysicalTypeID::DOUBLE: {
        return GetFloatCompressionMetadata<double>(alg, dataType)(buffer, numValues, min, max);
    }
    case PhysicalTypeID::FLOAT: {
        return GetFloatCompressionMetadata<float>(alg, dataType)(buffer, numValues, min, max);
    }
    default: {
        return uncompressedGetMetadata(dataType.getPhysicalType(), numValues, min, max);
    }
    }
}

ColumnChunkMetadata uncompressedGetMetadata(PhysicalTypeID dataType, uint64_t numValues,
    StorageValue min, StorageValue max) {
    auto numPages = 0;
    if (getDataTypeSizeInChunk(dataType) > 0) {
        const auto numValuesPerPage = Uncompressed::numValues(LBUG_PAGE_SIZE, dataType);
        numPages = ceilDiv(numValues, numValuesPerPage);
    }
    return ColumnChunkMetadata(INVALID_PAGE_IDX, numPages, numValues,
        CompressionMetadata(min, max, CompressionType::UNCOMPRESSED));
}

ColumnChunkMetadata booleanGetMetadata(uint64_t numValues, StorageValue min, StorageValue max) {
    return ColumnChunkMetadata(INVALID_PAGE_IDX,
        ceilDiv(ceilDiv(numValues, uint64_t{8}), LBUG_PAGE_SIZE), numValues,
        CompressionMetadata(min, max, CompressionType::BOOLEAN_BITPACKING));
}

void ColumnChunkMetadata::serialize(common::Serializer& serializer) const {
    serializer.write(pageRange.startPageIdx);
    serializer.write(pageRange.numPages);
    serializer.write(numValues);
    compMeta.serialize(serializer);
}

ColumnChunkMetadata ColumnChunkMetadata::deserialize(common::Deserializer& deserializer) {
    ColumnChunkMetadata ret;
    deserializer.deserializeValue(ret.pageRange.startPageIdx);
    deserializer.deserializeValue(ret.pageRange.numPages);
    deserializer.deserializeValue(ret.numValues);
    ret.compMeta = decltype(ret.compMeta)::deserialize(deserializer);

    return ret;
}

page_idx_t ColumnChunkMetadata::getNumDataPages(PhysicalTypeID dataType) const {
    switch (compMeta.compression) {
    case CompressionType::ALP: {
        return TypeUtils::visit(
            dataType,
            [this]<std::floating_point T>(T) -> page_idx_t {
                return FloatCompression<T>::getNumDataPages(getNumPages(), compMeta);
            },
            [](auto) -> page_idx_t { KU_UNREACHABLE; });
    }
    default:
        return getNumPages();
    }
}

ColumnChunkMetadata GetBitpackingMetadata::operator()(std::span<const uint8_t> /*buffer*/,
    uint64_t numValues, StorageValue min, StorageValue max) {
    // For supported types, min and max may be null if all values are null
    // Compression is supported in this case
    // Unsupported types always return a dummy value (where min != max)
    // so that we don't constant compress them
    auto compMeta = CompressionMetadata(min, max, alg->getCompressionType());
    if (alg->getCompressionType() == CompressionType::INTEGER_BITPACKING) {
        TypeUtils::visit(
            dataType.getPhysicalType(),
            [&]<IntegerBitpackingType T>(T) {
                // If integer bitpacking bitwidth is the maximum, bitpacking cannot be used
                // and has poor performance compared to uncompressed
                if (IntegerBitpacking<T>::getPackingInfo(compMeta).bitWidth >= sizeof(T) * 8) {
                    compMeta = CompressionMetadata(min, max, CompressionType::UNCOMPRESSED);
                }
            },
            [&](auto) {});
    }
    const auto numValuesPerPage = compMeta.numValues(LBUG_PAGE_SIZE, dataType);
    const auto numPages =
        numValuesPerPage == UINT64_MAX ?
            0 :
            numValues / numValuesPerPage + (numValues % numValuesPerPage == 0 ? 0 : 1);
    return ColumnChunkMetadata(INVALID_PAGE_IDX, numPages, numValues, compMeta);
}

namespace {
ColumnChunkMetadata getConstantFloatMetadata(PhysicalTypeID physicalType, uint64_t numValues,
    StorageValue min, StorageValue max) {
    return {INVALID_PAGE_IDX, 0, numValues,
        CompressionMetadata(min, max, CompressionType::CONSTANT, alp::state{}, StorageValue{0},
            StorageValue{0}, physicalType)};
}

template<std::floating_point T>
alp::state getAlpMetadata(const T* buffer, uint64_t numValues) {
    alp::state alpMetadata;
    std::vector<T> sampleBuffer(alp::config::SAMPLES_PER_ROWGROUP);
    alp::AlpEncode<T>::init(buffer, 0, numValues, sampleBuffer.data(), alpMetadata);

    if (alpMetadata.scheme == alp::SCHEME::ALP) {
        if (alpMetadata.k_combinations > 1) {
            alp::AlpEncode<T>::find_best_exponent_factor_from_combinations(
                alpMetadata.best_k_combinations, alpMetadata.k_combinations, buffer, numValues,
                alpMetadata.fac, alpMetadata.exp);
        } else {
            KU_ASSERT(alpMetadata.best_k_combinations.size() == 1);
            alpMetadata.exp = alpMetadata.best_k_combinations[0].first;
            alpMetadata.fac = alpMetadata.best_k_combinations[0].second;
        }
    }

    return alpMetadata;
}

template<std::floating_point T>
CompressionMetadata createFloatMetadata(CompressionType compressionType,
    PhysicalTypeID physicalType, std::span<const T> src, alp::state& alpMetadata, StorageValue min,
    StorageValue max) {
    using EncodedType = typename FloatCompression<T>::EncodedType;

    offset_vec_t unsuccessfulEncodeIdxes;
    std::vector<EncodedType> floatEncodedValues(src.size());
    std::optional<EncodedType> firstSuccessfulEncode;
    size_t exceptionCount = 0;
    for (offset_t i = 0; i < src.size(); ++i) {
        const T& val = src[i];
        const auto encoded_value =
            alp::AlpEncode<T>::encode_value(val, alpMetadata.fac, alpMetadata.exp);
        const auto decoded_value =
            alp::AlpDecode<T>::decode_value(encoded_value, alpMetadata.fac, alpMetadata.exp);

        if (val == decoded_value) {
            floatEncodedValues[i] = encoded_value;
            if (!firstSuccessfulEncode.has_value()) {
                firstSuccessfulEncode = encoded_value;
            }
        } else {
            unsuccessfulEncodeIdxes.push_back(i);
            ++exceptionCount;
        }
    }
    alpMetadata.exceptions_count = exceptionCount;

    if (firstSuccessfulEncode.has_value()) {
        for (auto unsuccessfulEncodeIdx : unsuccessfulEncodeIdxes) {
            floatEncodedValues[unsuccessfulEncodeIdx] = firstSuccessfulEncode.value();
        }
    }

    const auto& [minEncoded, maxEncoded] =
        std::minmax_element(floatEncodedValues.begin(), floatEncodedValues.end());

    return CompressionMetadata(min, max, compressionType, alpMetadata, StorageValue{*minEncoded},
        StorageValue{*maxEncoded}, physicalType);
}
} // namespace

template<std::floating_point T>
ColumnChunkMetadata GetFloatCompressionMetadata<T>::operator()(std::span<const uint8_t> buffer,
    uint64_t numValues, StorageValue min, StorageValue max) {
    const PhysicalTypeID physicalType =
        std::same_as<T, double> ? PhysicalTypeID::DOUBLE : PhysicalTypeID::FLOAT;

    if (min == max) {
        return getConstantFloatMetadata(physicalType, numValues, min, max);
    }

    if (numValues == 0) {
        return uncompressedGetMetadata(physicalType, numValues, min, max);
    }

    std::span<const T> castedBuffer{reinterpret_cast<const T*>(buffer.data()), (size_t)numValues};
    alp::state alpMetadata = getAlpMetadata<T>(castedBuffer.data(), numValues);
    if (alpMetadata.scheme != alp::SCHEME::ALP) {
        return uncompressedGetMetadata(physicalType, numValues, min, max);
    }

    const auto compMeta = createFloatMetadata(alg->getCompressionType(), physicalType, castedBuffer,
        alpMetadata, min, max);
    const auto* floatMetadata = compMeta.floatMetadata();
    const auto exceptionCount = floatMetadata->exceptionCount;

    if (exceptionCount * FloatCompression<T>::MAX_EXCEPTION_FACTOR >= numValues) {
        return uncompressedGetMetadata(physicalType, numValues, min, max);
    }

    const auto numValuesPerPage = compMeta.numValues(LBUG_PAGE_SIZE, dataType);
    const auto numPagesForEncoded = ceilDiv(numValues, numValuesPerPage);
    const auto numPagesForExceptions =
        EncodeException<T>::numPagesFromExceptions(floatMetadata->exceptionCapacity);
    return ColumnChunkMetadata(INVALID_PAGE_IDX, numPagesForEncoded + numPagesForExceptions,
        numValues, compMeta);
}

template class GetFloatCompressionMetadata<float>;
template class GetFloatCompressionMetadata<double>;
} // namespace lbug::storage
