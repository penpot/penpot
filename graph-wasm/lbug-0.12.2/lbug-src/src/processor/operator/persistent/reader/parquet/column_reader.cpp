#include "processor/operator/persistent/reader/parquet/column_reader.h"

#include <sstream>

#include "brotli/decode.h"
#include "common/assert.h"
#include "common/exception/not_implemented.h"
#include "common/exception/runtime.h"
#include "common/types/date_t.h"
#include "lz4.hpp"
#include "miniz_wrapper.hpp"
#include "processor/operator/persistent/reader/parquet/boolean_column_reader.h"
#include "processor/operator/persistent/reader/parquet/callback_column_reader.h"
#include "processor/operator/persistent/reader/parquet/interval_column_reader.h"
#include "processor/operator/persistent/reader/parquet/parquet_timestamp.h"
#include "processor/operator/persistent/reader/parquet/string_column_reader.h"
#include "processor/operator/persistent/reader/parquet/templated_column_reader.h"
#include "processor/operator/persistent/reader/parquet/uuid_column_reader.h"
#include "snappy.h"
#include "zstd.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

using lbug_parquet::format::CompressionCodec;
using lbug_parquet::format::ConvertedType;
using lbug_parquet::format::Encoding;
using lbug_parquet::format::PageType;
using lbug_parquet::format::Type;

ColumnReader::ColumnReader(ParquetReader& reader, LogicalType type,
    const lbug_parquet::format::SchemaElement& schema, idx_t fileIdx, uint64_t maxDefinition,
    uint64_t maxRepeat)
    : schema{schema}, fileIdx{fileIdx}, maxDefine{maxDefinition}, maxRepeat{maxRepeat},
      reader{reader}, type{std::move(type)}, protocol(nullptr), pageRowsAvailable{0},
      groupRowsAvailable(0), chunkReadOffset(0) {}

void ColumnReader::initializeRead(uint64_t /*rowGroupIdx*/,
    const std::vector<lbug_parquet::format::ColumnChunk>& columns,
    lbug_apache::thrift::protocol::TProtocol& protocol) {
    KU_ASSERT(fileIdx < columns.size());
    chunk = &columns[fileIdx];
    this->protocol = &protocol;
    KU_ASSERT(chunk);
    KU_ASSERT(chunk->__isset.meta_data);

    if (chunk->__isset.file_path) {
        throw std::runtime_error("Only inlined data files are supported (no references)");
    }

    // ugh. sometimes there is an extra offset for the dict. sometimes it's wrong.
    chunkReadOffset = chunk->meta_data.data_page_offset;
    if (chunk->meta_data.__isset.dictionary_page_offset &&
        chunk->meta_data.dictionary_page_offset >= 4) {
        // this assumes the data pages follow the dict pages directly.
        chunkReadOffset = chunk->meta_data.dictionary_page_offset;
    }
    groupRowsAvailable = chunk->meta_data.num_values;
}

void ColumnReader::registerPrefetch(ThriftFileTransport& transport, bool allowMerge) {
    if (chunk) {
        uint64_t size = chunk->meta_data.total_compressed_size;
        transport.RegisterPrefetch(fileOffset(), size, allowMerge);
    }
}

uint64_t ColumnReader::fileOffset() const {
    if (!chunk) {
        throw std::runtime_error("fileOffset called on ColumnReader with no chunk");
    }
    auto minOffset = UINT64_MAX;
    if (chunk->meta_data.__isset.dictionary_page_offset) {
        minOffset = std::min<uint64_t>(minOffset, chunk->meta_data.dictionary_page_offset);
    }
    if (chunk->meta_data.__isset.index_page_offset) {
        minOffset = std::min<uint64_t>(minOffset, chunk->meta_data.index_page_offset);
    }
    minOffset = std::min<uint64_t>(minOffset, chunk->meta_data.data_page_offset);

    return minOffset;
}

void ColumnReader::applyPendingSkips(uint64_t numValues) {
    pendingSkips -= numValues;

    dummyDefine.zero();
    dummyRepeat.zero();

    // TODO this can be optimized, for example we dont actually have to bitunpack offsets
    std::unique_ptr<common::ValueVector> dummyResult =
        std::make_unique<common::ValueVector>(type.copy());

    uint64_t remaining = numValues;
    uint64_t numValuesRead = 0;

    while (remaining) {
        auto numValuesToRead = std::min<uint64_t>(remaining, common::DEFAULT_VECTOR_CAPACITY);
        numValuesRead +=
            read(numValuesToRead, noneFilter, dummyDefine.ptr, dummyRepeat.ptr, dummyResult.get());
        remaining -= numValuesToRead;
    }

    if (numValuesRead != numValues) {
        throw std::runtime_error("Row count mismatch when skipping rows");
    }
}

uint64_t ColumnReader::read(uint64_t numValues, parquet_filter_t& filter, uint8_t* defineOut,
    uint8_t* repeatOut, common::ValueVector* resultOut) {
    // we need to reset the location because multiple column readers share the same protocol
    auto& trans = reinterpret_cast<ThriftFileTransport&>(*protocol->getTransport());
    trans.SetLocation(chunkReadOffset);

    // Perform any skips that were not applied yet.
    if (pendingSkips > 0) {
        applyPendingSkips(pendingSkips);
    }

    uint64_t resultOffset = 0;
    auto toRead = numValues;

    while (toRead > 0) {
        while (pageRowsAvailable == 0) {
            prepareRead(filter);
        }

        KU_ASSERT(block);
        auto readNow = std::min<uint64_t>(toRead, pageRowsAvailable);

        KU_ASSERT(readNow <= common::DEFAULT_VECTOR_CAPACITY);

        if (hasRepeats()) {
            KU_ASSERT(repeatedDecoder);
            repeatedDecoder->GetBatch<uint8_t>(repeatOut + resultOffset, readNow);
        }

        if (hasDefines()) {
            KU_ASSERT(defineDecoder);
            defineDecoder->GetBatch<uint8_t>(defineOut + resultOffset, readNow);
        }

        uint64_t nullCount = 0;

        if ((dictDecoder || dbpDecoder || rleDecoder) && hasDefines()) {
            // we need the null count because the dictionary offsets have no entries for nulls
            for (auto i = 0u; i < readNow; i++) {
                if (defineOut[i + resultOffset] != maxDefine) {
                    nullCount++;
                }
            }
        }

        if (dictDecoder) {
            offsetBuffer.resize(sizeof(uint32_t) * (readNow - nullCount));
            dictDecoder->GetBatch<uint32_t>(offsetBuffer.ptr, readNow - nullCount);
            offsets(reinterpret_cast<uint32_t*>(offsetBuffer.ptr), defineOut, readNow, filter,
                resultOffset, resultOut);
        } else if (dbpDecoder) {
            // TODO keep this in the state
            auto readBuf = std::make_shared<ResizeableBuffer>();

            switch (type.getPhysicalType()) {
            case common::PhysicalTypeID::INT32:
                readBuf->resize(sizeof(int32_t) * (readNow - nullCount));
                dbpDecoder->GetBatch<int32_t>(readBuf->ptr, readNow - nullCount);
                break;
            case common::PhysicalTypeID::INT64:
                readBuf->resize(sizeof(int64_t) * (readNow - nullCount));
                dbpDecoder->GetBatch<int64_t>(readBuf->ptr, readNow - nullCount);
                break;
            default:
                throw std::runtime_error("DELTA_BINARY_PACKED should only be INT32 or INT64");
            }
            // Plain() will put NULLs in the right place
            plain(readBuf, defineOut, readNow, filter, resultOffset, resultOut);
        } else if (rleDecoder) {
            // RLE encoding for boolean
            KU_ASSERT(type.getLogicalTypeID() == common::LogicalTypeID::BOOL);
            auto readBuf = std::make_shared<ResizeableBuffer>();
            readBuf->resize(sizeof(bool) * (readNow - nullCount));
            rleDecoder->GetBatch<uint8_t>(readBuf->ptr, readNow - nullCount);
            plainTemplated<bool, TemplatedParquetValueConversion<bool>>(readBuf, defineOut, readNow,
                filter, resultOffset, resultOut);
        } else {
            plain(block, defineOut, readNow, filter, resultOffset, resultOut);
        }

        resultOffset += readNow;
        pageRowsAvailable -= readNow;
        toRead -= readNow;
    }
    groupRowsAvailable -= numValues;
    chunkReadOffset = trans.GetLocation();

    return numValues;
}

std::unique_ptr<ColumnReader> ColumnReader::createReader(ParquetReader& reader,
    common::LogicalType type, const lbug_parquet::format::SchemaElement& schema, uint64_t fileIdx,
    uint64_t maxDefine, uint64_t maxRepeat) {
    switch (type.getLogicalTypeID()) {
    case common::LogicalTypeID::BOOL:
        return std::make_unique<BooleanColumnReader>(reader, std::move(type), schema, fileIdx,
            maxDefine, maxRepeat);
    case common::LogicalTypeID::INT8:
        return std::make_unique<
            TemplatedColumnReader<int8_t, TemplatedParquetValueConversion<int32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::INT16:
        return std::make_unique<
            TemplatedColumnReader<int16_t, TemplatedParquetValueConversion<int32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::INT32:
        return std::make_unique<
            TemplatedColumnReader<int32_t, TemplatedParquetValueConversion<int32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::SERIAL:
    case common::LogicalTypeID::INT64:
        return std::make_unique<
            TemplatedColumnReader<int64_t, TemplatedParquetValueConversion<int64_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::UINT8:
        return std::make_unique<
            TemplatedColumnReader<uint8_t, TemplatedParquetValueConversion<uint32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::UINT16:
        return std::make_unique<
            TemplatedColumnReader<uint16_t, TemplatedParquetValueConversion<uint32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::UINT32:
        return std::make_unique<
            TemplatedColumnReader<uint32_t, TemplatedParquetValueConversion<uint32_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::UINT64:
        return std::make_unique<
            TemplatedColumnReader<uint64_t, TemplatedParquetValueConversion<uint64_t>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::FLOAT:
        return std::make_unique<
            TemplatedColumnReader<float, TemplatedParquetValueConversion<float>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::DOUBLE:
        return std::make_unique<
            TemplatedColumnReader<double, TemplatedParquetValueConversion<double>>>(reader,
            std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::DATE:
        return std::make_unique<
            CallbackColumnReader<int32_t, common::date_t, ParquetTimeStampUtils::parquetIntToDate>>(
            reader, std::move(type), schema, fileIdx, maxDefine, maxRepeat);
    case common::LogicalTypeID::BLOB:
    case common::LogicalTypeID::STRING:
        return std::make_unique<StringColumnReader>(reader, std::move(type), schema, fileIdx,
            maxDefine, maxRepeat);
    case common::LogicalTypeID::INTERVAL:
        return std::make_unique<IntervalColumnReader>(reader, std::move(type), schema, fileIdx,
            maxDefine, maxRepeat);
    case common::LogicalTypeID::TIMESTAMP_TZ:
    case common::LogicalTypeID::TIMESTAMP:
        return createTimestampReader(reader, std::move(type), schema, fileIdx, maxDefine,
            maxRepeat);
    case common::LogicalTypeID::UUID:
        return std::make_unique<UUIDColumnReader>(reader, std::move(type), schema, fileIdx,
            maxDefine, maxRepeat);
    default:
        KU_UNREACHABLE;
    }
}

void ColumnReader::prepareRead(parquet_filter_t& /*filter*/) {
    dictDecoder.reset();
    defineDecoder.reset();
    block.reset();
    lbug_parquet::format::PageHeader pageHdr;
    pageHdr.read(protocol);

    switch (pageHdr.type) {
    case PageType::DATA_PAGE_V2:
        preparePageV2(pageHdr);
        prepareDataPage(pageHdr);
        break;
    case PageType::DATA_PAGE:
        preparePage(pageHdr);
        prepareDataPage(pageHdr);
        break;
    case PageType::DICTIONARY_PAGE:
        preparePage(pageHdr);
        dictionary(block, pageHdr.dictionary_page_header.num_values);
        break;
    default:
        break; // ignore INDEX page type and any other custom extensions
    }
    resetPage();
}

void ColumnReader::allocateBlock(uint64_t size) {
    if (!block) {
        block = std::make_shared<ResizeableBuffer>(size);
    } else {
        block->resize(size);
    }
}

void ColumnReader::allocateCompressed(uint64_t size) {
    compressedBuffer.resize(size);
}

static void brotliDecompress(uint8_t* dst, size_t dstSize, const uint8_t* src, size_t srcSize) {
    auto instance = BrotliDecoderCreateInstance(nullptr /* alloc_func */, nullptr /* free_func */,
        nullptr /* opaque */);
    BrotliDecoderResult oneshotResult{};
    do {
        oneshotResult =
            BrotliDecoderDecompressStream(instance, &srcSize, &src, &dstSize, &dst, nullptr);
    } while (srcSize != 0 || oneshotResult != BROTLI_DECODER_RESULT_SUCCESS);
    BrotliDecoderDestroyInstance(instance);
}

void ColumnReader::decompressInternal(lbug_parquet::format::CompressionCodec::type codec,
    const uint8_t* src, uint64_t srcSize, uint8_t* dst, uint64_t dstSize) {
    switch (codec) {
    case CompressionCodec::UNCOMPRESSED:
        throw common::CopyException("Parquet data unexpectedly uncompressed");
    case CompressionCodec::GZIP: {
        MiniZStream s;
        s.Decompress(reinterpret_cast<const char*>(src), srcSize, reinterpret_cast<char*>(dst),
            dstSize);
    } break;
    case CompressionCodec::SNAPPY: {
        {
            size_t uncompressedSize = 0;
            auto res = lbug_snappy::GetUncompressedLength(reinterpret_cast<const char*>(src),
                srcSize, &uncompressedSize);
            // LCOV_EXCL_START
            if (!res) {
                throw common::RuntimeException{"Failed to decompress parquet file."};
            }
            if (uncompressedSize != (size_t)dstSize) {
                throw common::RuntimeException{
                    "Snappy decompression failure: Uncompressed data size mismatch"};
            }
            // LCOV_EXCL_STOP
        }
        auto res = lbug_snappy::RawUncompress(reinterpret_cast<const char*>(src), srcSize,
            reinterpret_cast<char*>(dst));
        // LCOV_EXCL_START
        if (!res) {
            throw common::RuntimeException{"Snappy decompression failed."};
        }
        // LCOV_EXCL_STOP
    } break;
    case CompressionCodec::ZSTD: {
        auto res = lbug_zstd::ZSTD_decompress(dst, dstSize, src, srcSize);
        // LCOV_EXCL_START
        if (lbug_zstd::ZSTD_isError(res) || res != (size_t)dstSize) {
            throw common::RuntimeException{"ZSTD decompression failed."};
        }
        // LCOV_EXCL_STOP
    } break;
    case CompressionCodec::BROTLI: {
        brotliDecompress(dst, dstSize, src, srcSize);
    } break;
    case CompressionCodec::LZ4_RAW: {
        auto res = lbug_lz4::LZ4_decompress_safe(reinterpret_cast<const char*>(src),
            reinterpret_cast<char*>(dst), srcSize, dstSize);
        // LCOV_EXCL_START
        if (res != (int64_t)dstSize) {
            throw common::RuntimeException{"LZ4 decompression failed."};
        }
        // LCOV_EXCL_STOP
    } break;
    default: {
        // LCOV_EXCL_START
        std::stringstream codec_name;
        codec_name << codec;
        throw common::CopyException("Unsupported compression codec \"" + codec_name.str() +
                                    "\". Supported options are uncompressed, gzip, snappy or zstd");
        // LCOV_EXCL_STOP
    }
    }
}

void ColumnReader::preparePageV2(lbug_parquet::format::PageHeader& pageHdr) {
    KU_ASSERT(pageHdr.type == PageType::DATA_PAGE_V2);

    auto& trans = reinterpret_cast<ThriftFileTransport&>(*protocol->getTransport());

    allocateBlock(pageHdr.uncompressed_page_size + 1);
    bool uncompressed = false;
    if (pageHdr.data_page_header_v2.__isset.is_compressed &&
        !pageHdr.data_page_header_v2.is_compressed) {
        uncompressed = true;
    }
    if (chunk->meta_data.codec == CompressionCodec::UNCOMPRESSED) {
        if (pageHdr.compressed_page_size != pageHdr.uncompressed_page_size) {
            throw std::runtime_error("Page size mismatch");
        }
        uncompressed = true;
    }
    if (uncompressed) {
        trans.read(block->ptr, pageHdr.compressed_page_size);
        return;
    }

    // copy repeats & defines as-is because FOR SOME REASON they are uncompressed
    auto uncompressedBytes = pageHdr.data_page_header_v2.repetition_levels_byte_length +
                             pageHdr.data_page_header_v2.definition_levels_byte_length;
    trans.read(block->ptr, uncompressedBytes);

    auto compressedBytes = pageHdr.compressed_page_size - uncompressedBytes;

    allocateCompressed(compressedBytes);
    trans.read(compressedBuffer.ptr, compressedBytes);

    decompressInternal(chunk->meta_data.codec, compressedBuffer.ptr, compressedBytes,
        block->ptr + uncompressedBytes, pageHdr.uncompressed_page_size - uncompressedBytes);
}

void ColumnReader::preparePage(lbug_parquet::format::PageHeader& pageHdr) {
    auto& trans = reinterpret_cast<ThriftFileTransport&>(*protocol->getTransport());

    allocateBlock(pageHdr.uncompressed_page_size + 1);
    if (chunk->meta_data.codec == CompressionCodec::UNCOMPRESSED) {
        if (pageHdr.compressed_page_size != pageHdr.uncompressed_page_size) {
            throw std::runtime_error("Page size mismatch");
        }
        trans.read((uint8_t*)block->ptr, pageHdr.compressed_page_size);
        return;
    }

    allocateCompressed(pageHdr.compressed_page_size + 1);
    trans.read((uint8_t*)compressedBuffer.ptr, pageHdr.compressed_page_size);

    decompressInternal(chunk->meta_data.codec, compressedBuffer.ptr, pageHdr.compressed_page_size,
        block->ptr, pageHdr.uncompressed_page_size);
}

void ColumnReader::prepareDataPage(lbug_parquet::format::PageHeader& pageHdr) {
    if (pageHdr.type == PageType::DATA_PAGE && !pageHdr.__isset.data_page_header) {
        throw std::runtime_error("Missing data page header from data page");
    }
    if (pageHdr.type == PageType::DATA_PAGE_V2 && !pageHdr.__isset.data_page_header_v2) {
        throw std::runtime_error("Missing data page header from data page v2");
    }

    bool isV1 = pageHdr.type == PageType::DATA_PAGE;
    bool isV2 = pageHdr.type == PageType::DATA_PAGE_V2;
    auto& v1Header = pageHdr.data_page_header;
    auto& v2Header = pageHdr.data_page_header_v2;

    pageRowsAvailable = isV1 ? v1Header.num_values : v2Header.num_values;
    auto pageEncoding = isV1 ? v1Header.encoding : v2Header.encoding;

    if (hasRepeats()) {
        uint32_t repLength =
            isV1 ? block->read<uint32_t>() : v2Header.repetition_levels_byte_length;
        block->available(repLength);
        repeatedDecoder = std::make_unique<RleBpDecoder>(block->ptr, repLength,
            RleBpDecoder::ComputeBitWidth(maxRepeat));
        block->inc(repLength);
    } else if (isV2 && v2Header.repetition_levels_byte_length > 0) {
        block->inc(v2Header.repetition_levels_byte_length);
    }

    if (hasDefines()) {
        auto defLen = isV1 ? block->read<uint32_t>() : v2Header.definition_levels_byte_length;
        block->available(defLen);
        defineDecoder = std::make_unique<RleBpDecoder>(block->ptr, defLen,
            RleBpDecoder::ComputeBitWidth(maxDefine));
        block->inc(defLen);
    } else if (isV2 && v2Header.definition_levels_byte_length > 0) {
        block->inc(v2Header.definition_levels_byte_length);
    }

    switch (pageEncoding) {
    case Encoding::RLE_DICTIONARY:
    case Encoding::PLAIN_DICTIONARY: {
        // where is it otherwise??
        auto dictWidth = block->read<uint8_t>();
        // TODO somehow dict_width can be 0 ?
        dictDecoder = std::make_unique<RleBpDecoder>(block->ptr, block->len, dictWidth);
        block->inc(block->len);
        break;
    }
    case Encoding::RLE: {
        if (type.getLogicalTypeID() != common::LogicalTypeID::BOOL) {
            throw std::runtime_error("RLE encoding is only supported for boolean data");
        }
        block->inc(sizeof(uint32_t));
        rleDecoder = std::make_unique<RleBpDecoder>(block->ptr, block->len, 1);
        break;
    }
    case Encoding::DELTA_BINARY_PACKED: {
        dbpDecoder = std::make_unique<DbpDecoder>(block->ptr, block->len);
        block->inc(block->len);
        break;
    }
    case Encoding::DELTA_LENGTH_BYTE_ARRAY:
    case Encoding::DELTA_BYTE_ARRAY: {
        KU_UNREACHABLE;
    }
    case Encoding::PLAIN:
        // nothing to do here, will be read directly below
        break;
    default:
        throw common::NotImplementedException("Parquet: unsupported page encoding");
    }
}

uint64_t ColumnReader::getTotalCompressedSize() {
    if (!chunk) {
        return 0;
    }
    return chunk->meta_data.total_compressed_size;
}

std::unique_ptr<ColumnReader> ColumnReader::createTimestampReader(ParquetReader& reader,
    common::LogicalType type, const lbug_parquet::format::SchemaElement& schema, uint64_t fileIdx,
    uint64_t maxDefine, uint64_t maxRepeat) {
    switch (schema.type) {
    case Type::INT96: {
        return std::make_unique<CallbackColumnReader<Int96, common::timestamp_t,
            ParquetTimeStampUtils::impalaTimestampToTimestamp>>(reader, std::move(type), schema,
            fileIdx, maxDefine, maxRepeat);
    }
    case Type::INT64: {
        if (schema.__isset.logicalType && schema.logicalType.__isset.TIMESTAMP) {
            if (schema.logicalType.TIMESTAMP.unit.__isset.MILLIS) {
                return std::make_unique<CallbackColumnReader<int64_t, common::timestamp_t,
                    ParquetTimeStampUtils::parquetTimestampMsToTimestamp>>(reader, std::move(type),
                    schema, fileIdx, maxDefine, maxRepeat);
            } else if (schema.logicalType.TIMESTAMP.unit.__isset.MICROS) {
                return std::make_unique<CallbackColumnReader<int64_t, common::timestamp_t,
                    ParquetTimeStampUtils::parquetTimestampMicrosToTimestamp>>(reader,
                    std::move(type), schema, fileIdx, maxDefine, maxRepeat);
            } else if (schema.logicalType.TIMESTAMP.unit.__isset.NANOS) {
                return std::make_unique<CallbackColumnReader<int64_t, common::timestamp_t,
                    ParquetTimeStampUtils::parquetTimestampNsToTimestamp>>(reader, std::move(type),
                    schema, fileIdx, maxDefine, maxRepeat);
            }
            // LCOV_EXCL_START
        } else if (schema.__isset.converted_type) {
            // For legacy compatibility.
            switch (schema.converted_type) {
            case ConvertedType::TIMESTAMP_MICROS:
                return std::make_unique<CallbackColumnReader<int64_t, common::timestamp_t,
                    ParquetTimeStampUtils::parquetTimestampMicrosToTimestamp>>(reader,
                    std::move(type), schema, fileIdx, maxDefine, maxRepeat);
            case ConvertedType::TIMESTAMP_MILLIS:
                return std::make_unique<CallbackColumnReader<int64_t, common::timestamp_t,
                    ParquetTimeStampUtils::parquetTimestampMsToTimestamp>>(reader, std::move(type),
                    schema, fileIdx, maxDefine, maxRepeat);
            default:
                KU_UNREACHABLE;
            }
            // LCOV_EXCL_STOP
        }
        KU_UNREACHABLE;
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

const uint64_t ParquetDecodeUtils::BITPACK_MASKS[] = {0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023,
    2047, 4095, 8191, 16383, 32767, 65535, 131071, 262143, 524287, 1048575, 2097151, 4194303,
    8388607, 16777215, 33554431, 67108863, 134217727, 268435455, 536870911, 1073741823, 2147483647,
    4294967295, 8589934591, 17179869183, 34359738367, 68719476735, 137438953471, 274877906943,
    549755813887, 1099511627775, 2199023255551, 4398046511103, 8796093022207, 17592186044415,
    35184372088831, 70368744177663, 140737488355327, 281474976710655, 562949953421311,
    1125899906842623, 2251799813685247, 4503599627370495, 9007199254740991, 18014398509481983,
    36028797018963967, 72057594037927935, 144115188075855871, 288230376151711743,
    576460752303423487, 1152921504606846975, 2305843009213693951, 4611686018427387903,
    9223372036854775807, 18446744073709551615ULL};

const uint64_t ParquetDecodeUtils::BITPACK_MASKS_SIZE =
    sizeof(ParquetDecodeUtils::BITPACK_MASKS) / sizeof(uint64_t);

const uint8_t ParquetDecodeUtils::BITPACK_DLEN = 8;

} // namespace processor
} // namespace lbug
