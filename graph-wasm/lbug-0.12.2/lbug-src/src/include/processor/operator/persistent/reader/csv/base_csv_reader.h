#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <string_view>

#include "common/copier_config/csv_reader_config.h"
#include "common/data_chunk/data_chunk.h"
#include "common/file_system/file_info.h"
#include "common/types/types.h"
#include "processor/operator/persistent/reader/copy_from_error.h"

namespace lbug {
namespace common {
struct FileScanInfo;
}
namespace main {
class ClientContext;
}

namespace processor {
class LocalFileErrorHandler;
class SharedFileErrorHandler;

struct CSVColumnInfo {
    uint64_t numColumns;
    std::vector<bool> columnSkips;
    common::column_id_t numWarningDataColumns;

    CSVColumnInfo(uint64_t numColumns, std::vector<bool> columnSkips,
        common::column_id_t numWarningDataColumns)
        : numColumns{numColumns}, columnSkips{std::move(columnSkips)},
          numWarningDataColumns(numWarningDataColumns) {}
    EXPLICIT_COPY_DEFAULT_MOVE(CSVColumnInfo);

private:
    CSVColumnInfo(const CSVColumnInfo& other)
        : numColumns{other.numColumns}, columnSkips{other.columnSkips},
          numWarningDataColumns(other.numWarningDataColumns) {}
};

class BaseCSVReader {
    friend class ParsingDriver;
    friend class SniffCSVNameAndTypeDriver;

public:
    // 1st element is number of successfully parsed rows
    // 2nd element is number of failed to parse rows
    using parse_result_t = std::pair<uint64_t, uint64_t>;

    BaseCSVReader(const std::string& filePath, common::idx_t fileIdx, common::CSVOption option,
        CSVColumnInfo columnInfo, main::ClientContext* context,
        LocalFileErrorHandler* errorHandler);

    virtual ~BaseCSVReader() = default;

    virtual uint64_t parseBlock(common::block_idx_t blockIdx, common::DataChunk& resultChunk) = 0;

    main::ClientContext* getClientContext() const { return context; }
    const common::CSVOption& getCSVOption() const { return option; }

    uint64_t getNumColumns() const { return columnInfo.numColumns; }
    bool skipColumn(common::idx_t idx) const {
        KU_ASSERT(idx < columnInfo.columnSkips.size());
        return columnInfo.columnSkips[idx];
    }
    bool isEOF() const;
    uint64_t getFileSize();
    // Get the file offset of the current buffer position.
    uint64_t getFileOffset() const;

    std::string reconstructLine(uint64_t startPosition, uint64_t endPosition, bool completedLine);

    static common::column_id_t appendWarningDataColumns(std::vector<std::string>& resultColumnNames,
        std::vector<common::LogicalType>& resultColumnTypes,
        const common::FileScanInfo& fileScanInfo);

    static PopulatedCopyFromError basePopulateErrorFunc(CopyFromFileError error,
        const SharedFileErrorHandler* sharedErrorHandler, BaseCSVReader* reader,
        std::string filePath);

    static common::idx_t getFileIdxFunc(const CopyFromFileError& error);

protected:
    template<typename Driver>
    bool addValue(Driver&, uint64_t rowNum, common::column_id_t columnIdx, std::string_view strVal,
        std::vector<uint64_t>& escapePositions);

    //! Read BOM and header.
    parse_result_t handleFirstBlock();

    //! If this finds a BOM, it advances `position`.
    void readBOM();
    parse_result_t readHeader();
    //! Reads a new buffer from the CSV file.
    //! Uses the start value to ensure the current value stays within the buffer.
    //! Modifies the start value to point to the new start of the current value.
    //! If start is NULL, none of the buffer is kept.
    //! Returns false if the file has been exhausted.
    bool readBuffer(uint64_t* start);

    //! Like ReadBuffer, but only reads if position >= bufferSize.
    //! If this returns true, buffer[position] is a valid character that we can read.
    inline bool maybeReadBuffer(uint64_t* start) {
        return position < bufferSize || readBuffer(start);
    }

    void handleCopyException(const std::string& message, bool mustThrow = false);

    template<typename Driver>
    parse_result_t parseCSV(Driver&);

    inline bool isNewLine(char c) { return c == '\n' || c == '\r'; }

protected:
    virtual bool handleQuotedNewline() = 0;

    void skipCurrentLine();

    void resetNumRowsInCurrentBlock();
    void increaseNumRowsInCurrentBlock(uint64_t numRows, uint64_t numErrors);
    uint64_t getNumRowsInCurrentBlock() const;
    uint32_t getRowOffsetInCurrentBlock() const;

    WarningSourceData getWarningSourceData() const;

protected:
    main::ClientContext* context;
    common::CSVOption option;
    CSVColumnInfo columnInfo;
    std::unique_ptr<common::FileInfo> fileInfo;

    common::block_idx_t currentBlockIdx;
    uint64_t numRowsInCurrentBlock;

    uint64_t curRowIdx;
    uint64_t numErrors;

    std::unique_ptr<char[]> buffer;
    uint64_t bufferIdx;
    std::atomic<uint64_t> bufferSize;
    std::atomic<uint64_t> position;
    LineContext lineContext;
    std::atomic<uint64_t> osFileOffset;
    common::idx_t fileIdx;

    LocalFileErrorHandler* errorHandler;

    bool rowEmpty = false;
};

} // namespace processor
} // namespace lbug
