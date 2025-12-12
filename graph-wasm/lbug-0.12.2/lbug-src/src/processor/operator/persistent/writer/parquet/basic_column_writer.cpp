#include "processor/operator/persistent/writer/parquet/basic_column_writer.h"

#include "common/constants.h"
#include "common/exception/runtime.h"
#include "function/cast/functions/numeric_limits.h"
#include "processor/operator/persistent/reader/parquet/parquet_rle_bp_decoder.h"
#include "processor/operator/persistent/writer//parquet/parquet_rle_bp_encoder.h"
#include "processor/operator/persistent/writer/parquet/parquet_writer.h"

namespace lbug {
namespace processor {

using namespace lbug_parquet::format;
using namespace lbug::common;

std::unique_ptr<ColumnWriterState> BasicColumnWriter::initializeWriteState(
    lbug_parquet::format::RowGroup& rowGroup) {
    auto result = std::make_unique<BasicColumnWriterState>(rowGroup, rowGroup.columns.size());
    registerToRowGroup(rowGroup);
    return result;
}

void BasicColumnWriter::prepare(ColumnWriterState& stateToPrepare, ColumnWriterState* parent,
    common::ValueVector* vector, uint64_t count) {
    auto& state = reinterpret_cast<BasicColumnWriterState&>(stateToPrepare);
    auto& colChunk = state.rowGroup.columns[state.colIdx];

    uint64_t start = 0;
    auto vcount = parent ? parent->definitionLevels.size() - state.definitionLevels.size() : count;
    auto parentIdx = state.definitionLevels.size();
    handleRepeatLevels(state, parent);
    handleDefineLevels(state, parent, vector, count, maxDefine, maxDefine - 1);

    auto vectorIdx = 0u;
    for (auto i = start; i < vcount; i++) {
        auto& pageInfo = state.pageInfo.back();
        pageInfo.rowCount++;
        colChunk.meta_data.num_values++;
        if (parent && !parent->isEmpty.empty() && parent->isEmpty[parentIdx + i]) {
            pageInfo.emptyCount++;
            continue;
        }
        if (!vector->isNull(vectorIdx)) {
            pageInfo.estimatedPageSize += getRowSize(vector, vectorIdx, state);
            if (pageInfo.estimatedPageSize >= ParquetConstants::MAX_UNCOMPRESSED_PAGE_SIZE) {
                PageInformation newInfo;
                newInfo.offset = pageInfo.offset + pageInfo.rowCount;
                state.pageInfo.push_back(newInfo);
            }
        }
        vectorIdx++;
    }
}

void BasicColumnWriter::beginWrite(ColumnWriterState& writerState) {
    auto& state = reinterpret_cast<BasicColumnWriterState&>(writerState);

    // Set up the page write info.
    state.statsState = initializeStatsState();
    for (auto pageIdx = 0u; pageIdx < state.pageInfo.size(); pageIdx++) {
        auto& pageInfo = state.pageInfo[pageIdx];
        if (pageInfo.rowCount == 0) {
            KU_ASSERT(pageIdx + 1 == state.pageInfo.size());
            state.pageInfo.erase(state.pageInfo.begin() + pageIdx);
            break;
        }
        PageWriteInformation writeInfo;
        // Set up the header.
        auto& hdr = writeInfo.pageHeader;
        hdr.compressed_page_size = 0;
        hdr.uncompressed_page_size = 0;
        hdr.type = PageType::DATA_PAGE;
        hdr.__isset.data_page_header = true;

        hdr.data_page_header.num_values = pageInfo.rowCount;
        hdr.data_page_header.encoding = getEncoding(state);
        hdr.data_page_header.definition_level_encoding = Encoding::RLE;
        hdr.data_page_header.repetition_level_encoding = Encoding::RLE;

        writeInfo.bufferWriter = std::make_shared<BufferWriter>();
        writeInfo.writer = std::make_unique<Serializer>(writeInfo.bufferWriter);
        writeInfo.writeCount = pageInfo.emptyCount;
        writeInfo.maxWriteCount = pageInfo.rowCount;
        writeInfo.pageState = initializePageState(state);

        writeInfo.compressedSize = 0;
        writeInfo.compressedData = nullptr;

        state.writeInfo.push_back(std::move(writeInfo));
    }

    nextPage(state);
}

void BasicColumnWriter::write(ColumnWriterState& writerState, common::ValueVector* vector,
    uint64_t count) {
    auto& state = reinterpret_cast<BasicColumnWriterState&>(writerState);

    uint64_t remaining = count;
    uint64_t offset = 0;
    while (remaining > 0) {
        auto& writeInfo = state.writeInfo[state.currentPage - 1];
        KU_ASSERT(writeInfo.bufferWriter != nullptr);
        auto writeCount =
            std::min<uint64_t>(remaining, writeInfo.maxWriteCount - writeInfo.writeCount);

        writeVector(*writeInfo.writer, state.statsState.get(), writeInfo.pageState.get(), vector,
            offset, offset + writeCount);

        writeInfo.writeCount += writeCount;
        if (writeInfo.writeCount == writeInfo.maxWriteCount) {
            nextPage(state);
        }
        offset += writeCount;
        remaining -= writeCount;
    }
}

void BasicColumnWriter::finalizeWrite(ColumnWriterState& writerState) {
    auto& state = reinterpret_cast<BasicColumnWriterState&>(writerState);
    auto& columnChunk = state.rowGroup.columns[state.colIdx];

    // Flush the last page (if any remains).
    flushPage(state);

    auto startOffset = writer.getOffset();
    auto pageOffset = startOffset;
    // Flush the dictionary.
    if (hasDictionary(state)) {
        columnChunk.meta_data.statistics.distinct_count = dictionarySize(state);
        columnChunk.meta_data.statistics.__isset.distinct_count = true;
        columnChunk.meta_data.dictionary_page_offset = pageOffset;
        columnChunk.meta_data.__isset.dictionary_page_offset = true;
        flushDictionary(state, state.statsState.get());
        pageOffset += state.writeInfo[0].compressedSize;
    }

    // Record the start position of the pages for this column.
    columnChunk.meta_data.data_page_offset = pageOffset;
    setParquetStatistics(state, columnChunk);

    // write the individual pages to disk
    uint64_t totalUncompressedSize = 0;
    for (auto& write_info : state.writeInfo) {
        KU_ASSERT(write_info.pageHeader.uncompressed_page_size > 0);
        auto header_start_offset = writer.getOffset();
        write_info.pageHeader.write(writer.getProtocol());
        // total uncompressed size in the column chunk includes the header size (!)
        totalUncompressedSize += writer.getOffset() - header_start_offset;
        totalUncompressedSize += write_info.pageHeader.uncompressed_page_size;
        writer.write(write_info.compressedData, write_info.compressedSize);
    }
    columnChunk.meta_data.total_compressed_size = writer.getOffset() - startOffset;
    columnChunk.meta_data.total_uncompressed_size = totalUncompressedSize;
}

void BasicColumnWriter::writeLevels(Serializer& serializer, const std::vector<uint16_t>& levels,
    uint64_t maxValue, uint64_t startOffset, uint64_t count) {
    if (levels.empty() || count == 0) {
        return;
    }

    // Write the levels using the RLE-BP encoding.
    auto bitWidth = RleBpDecoder::ComputeBitWidth((maxValue));
    RleBpEncoder rleEncoder(bitWidth);

    rleEncoder.beginPrepare(levels[startOffset]);
    for (auto i = startOffset + 1; i < startOffset + count; i++) {
        rleEncoder.prepareValue(levels[i]);
    }
    rleEncoder.finishPrepare();

    // Start off by writing the byte count as a uint32_t.
    serializer.write<uint32_t>(rleEncoder.getByteCount());
    rleEncoder.beginWrite(levels[startOffset]);
    for (auto i = startOffset + 1; i < startOffset + count; i++) {
        rleEncoder.writeValue(serializer, levels[i]);
    }
    rleEncoder.finishWrite(serializer);
}

void BasicColumnWriter::nextPage(BasicColumnWriterState& state) {
    if (state.currentPage > 0) {
        // Need to flush the current page.
        flushPage(state);
    }
    if (state.currentPage >= state.writeInfo.size()) {
        state.currentPage = state.writeInfo.size() + 1;
        return;
    }
    auto& pageInfo = state.pageInfo[state.currentPage];
    auto& writeInfo = state.writeInfo[state.currentPage];
    state.currentPage++;

    // write the repetition levels
    writeLevels(*writeInfo.writer, state.repetitionLevels, maxRepeat, pageInfo.offset,
        pageInfo.rowCount);

    // write the definition levels
    writeLevels(*writeInfo.writer, state.definitionLevels, maxDefine, pageInfo.offset,
        pageInfo.rowCount);
}

void BasicColumnWriter::flushPage(BasicColumnWriterState& state) {
    KU_ASSERT(state.currentPage > 0);
    if (state.currentPage > state.writeInfo.size()) {
        return;
    }

    // compress the page info
    auto& writeInfo = state.writeInfo[state.currentPage - 1];
    auto& bufferedWriter = *writeInfo.bufferWriter;
    auto& hdr = writeInfo.pageHeader;

    flushPageState(*writeInfo.writer, writeInfo.pageState.get());

    // now that we have finished writing the data we know the uncompressed size
    if (bufferedWriter.getSize() > uint64_t(function::NumericLimits<int32_t>::maximum())) {
        throw common::RuntimeException{common::stringFormat(
            "Parquet writer: %d uncompressed page size out of range for type integer",
            bufferedWriter.getSize())};
    }
    hdr.uncompressed_page_size = bufferedWriter.getSize();

    // compress the data
    compressPage(bufferedWriter, writeInfo.compressedSize, writeInfo.compressedData,
        writeInfo.compressedBuf);
    hdr.compressed_page_size = writeInfo.compressedSize;
    KU_ASSERT(hdr.uncompressed_page_size > 0);
    KU_ASSERT(hdr.compressed_page_size > 0);

    if (writeInfo.compressedBuf) {
        // if the data has been compressed, we no longer need the compressed data
        KU_ASSERT(writeInfo.compressedBuf.get() == writeInfo.compressedData);
        writeInfo.bufferWriter.reset();
    }
}

void BasicColumnWriter::writeDictionary(BasicColumnWriterState& state,
    std::unique_ptr<BufferWriter> bufferedSerializer, uint64_t rowCount) {
    KU_ASSERT(bufferedSerializer);
    KU_ASSERT(bufferedSerializer->getSize() > 0);

    // write the dictionary page header
    PageWriteInformation writeInfo;
    // set up the header
    auto& hdr = writeInfo.pageHeader;
    hdr.uncompressed_page_size = bufferedSerializer->getSize();
    hdr.type = PageType::DICTIONARY_PAGE;
    hdr.__isset.dictionary_page_header = true;

    hdr.dictionary_page_header.encoding = Encoding::PLAIN;
    hdr.dictionary_page_header.is_sorted = false;
    hdr.dictionary_page_header.num_values = rowCount;

    writeInfo.bufferWriter = std::move(bufferedSerializer);
    writeInfo.writer = std::make_unique<Serializer>(writeInfo.bufferWriter);
    writeInfo.writeCount = 0;
    writeInfo.maxWriteCount = 0;

    // compress the contents of the dictionary page
    compressPage(*writeInfo.bufferWriter, writeInfo.compressedSize, writeInfo.compressedData,
        writeInfo.compressedBuf);
    hdr.compressed_page_size = writeInfo.compressedSize;

    // insert the dictionary page as the first page to write for this column
    state.writeInfo.insert(state.writeInfo.begin(), std::move(writeInfo));
}

void BasicColumnWriter::setParquetStatistics(BasicColumnWriterState& state,
    lbug_parquet::format::ColumnChunk& column) {
    if (maxRepeat == 0) {
        column.meta_data.statistics.null_count = nullCount;
        column.meta_data.statistics.__isset.null_count = true;
        column.meta_data.__isset.statistics = true;
    }
    // set min/max/min_value/max_value
    // this code is not going to win any beauty contests, but well
    auto min = state.statsState->getMin();
    if (!min.empty()) {
        column.meta_data.statistics.min = std::move(min);
        column.meta_data.statistics.__isset.min = true;
        column.meta_data.__isset.statistics = true;
    }
    auto max = state.statsState->getMax();
    if (!max.empty()) {
        column.meta_data.statistics.max = std::move(max);
        column.meta_data.statistics.__isset.max = true;
        column.meta_data.__isset.statistics = true;
    }
    auto min_value = state.statsState->getMinValue();
    if (!min_value.empty()) {
        column.meta_data.statistics.min_value = std::move(min_value);
        column.meta_data.statistics.__isset.min_value = true;
        column.meta_data.__isset.statistics = true;
    }
    auto max_value = state.statsState->getMaxValue();
    if (!max_value.empty()) {
        column.meta_data.statistics.max_value = std::move(max_value);
        column.meta_data.statistics.__isset.max_value = true;
        column.meta_data.__isset.statistics = true;
    }
    for (const auto& write_info : state.writeInfo) {
        column.meta_data.encodings.push_back(write_info.pageHeader.data_page_header.encoding);
    }
}

void BasicColumnWriter::registerToRowGroup(lbug_parquet::format::RowGroup& rowGroup) {
    ColumnChunk column_chunk;
    column_chunk.__isset.meta_data = true;
    column_chunk.meta_data.codec = writer.getCodec();
    column_chunk.meta_data.path_in_schema = schemaPath;
    column_chunk.meta_data.num_values = 0;
    column_chunk.meta_data.type = writer.getParquetType(schemaIdx);
    rowGroup.columns.push_back(std::move(column_chunk));
}

} // namespace processor
} // namespace lbug
