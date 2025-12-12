#include "processor/operator/persistent/reader/csv/driver.h"

#include "common/string_format.h"
#include "common/system_config.h"
#include "function/cast/functions/cast_from_string_functions.h"
#include "processor/operator/persistent/reader/csv/parallel_csv_reader.h"
#include "processor/operator/persistent/reader/csv/serial_csv_reader.h"
#include "utf8proc_wrapper.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

ParsingDriver::ParsingDriver(common::DataChunk& chunk, DriverType type /* = DriverType::PARSING */)
    : driverType(type), chunk(chunk), rowEmpty(false) {}

bool ParsingDriver::done(uint64_t rowNum) {
    return rowNum >= DEFAULT_VECTOR_CAPACITY || doneEarly();
}

bool ParsingDriver::addValue(uint64_t rowNum, common::column_id_t columnIdx,
    std::string_view value) {
    uint64_t length = value.length();
    if (length == 0 && columnIdx == 0) {
        rowEmpty = true;
    } else {
        rowEmpty = false;
    }
    BaseCSVReader* reader = getReader();

    if (columnIdx == reader->getNumColumns() && length == 0) {
        // skip a single trailing delimiter in last columnIdx
        return true;
    }
    if (columnIdx >= reader->getNumColumns()) {
        reader->handleCopyException(
            stringFormat("expected {} values per row, but got more.", reader->getNumColumns()));
        return false;
    }
    if (reader->skipColumn(columnIdx)) {
        return true;
    }
    try {
        function::CastString::copyStringToVector(&chunk.getValueVectorMutable(columnIdx), rowNum,
            value, &reader->option);
    } catch (ConversionException& e) {
        reader->handleCopyException(e.what());
        return false;
    }

    return true;
}

bool ParsingDriver::addRow(uint64_t rowNum, common::column_id_t columnCount,
    std::optional<WarningDataWithColumnInfo> warningDataWithColumnInfo) {
    BaseCSVReader* reader = getReader();
    if (rowEmpty) {
        rowEmpty = false;
        if (reader->getNumColumns() != 1) {
            return false;
        }
        // Otherwise, treat it as null.
    }
    if (columnCount < reader->getNumColumns()) {
        // Column number mismatch.
        reader->handleCopyException(stringFormat("expected {} values per row, but got {}.",
            reader->getNumColumns(), columnCount));
        return false;
    }

    if (warningDataWithColumnInfo.has_value()) {
        const auto warningDataStartColumn = warningDataWithColumnInfo->warningDataStartColumnIdx;
        const auto numWarningDataColumns = warningDataWithColumnInfo->data.numValues;
        KU_ASSERT(numWarningDataColumns == CopyConstants::CSV_WARNING_DATA_NUM_COLUMNS);
        for (idx_t i = 0; i < numWarningDataColumns; ++i) {
            const auto& warningData = warningDataWithColumnInfo->data.values[i];
            const auto columnIdx = warningDataStartColumn + i;
            KU_ASSERT(columnIdx < chunk.getNumValueVectors());
            auto& vectorToSet = chunk.getValueVectorMutable(columnIdx);
            std::visit(
                [&vectorToSet, rowNum](
                    auto warningDataField) { vectorToSet.setValue(rowNum, warningDataField); },
                warningData);
        }
    }
    return true;
}

ParallelParsingDriver::ParallelParsingDriver(common::DataChunk& chunk, ParallelCSVReader* reader)
    : ParsingDriver(chunk, DriverType::PARALLEL), reader(reader) {}

bool ParallelParsingDriver::doneEarly() {
    return reader->finishedBlock();
}

BaseCSVReader* ParallelParsingDriver::getReader() {
    return reader;
}

SerialParsingDriver::SerialParsingDriver(common::DataChunk& chunk, SerialCSVReader* reader,
    DriverType type /*= DriverType::SERIAL*/)
    : ParsingDriver(chunk, type), reader(reader) {}

bool SerialParsingDriver::doneEarly() {
    return false;
}

BaseCSVReader* SerialParsingDriver::getReader() {
    return reader;
}

common::DataChunk& getDummyDataChunk() {
    static common::DataChunk dummyChunk = DataChunk(); // static ensures it's created only once
    return dummyChunk;
}

SniffCSVDialectDriver::SniffCSVDialectDriver(SerialCSVReader* reader)
    : SerialParsingDriver(getDummyDataChunk(), reader, DriverType::SNIFF_CSV_DIALECT) {
    auto& csvOption = reader->getCSVOption();
    columnCounts = std::vector<idx_t>(csvOption.sampleSize, 0);
}

bool SniffCSVDialectDriver::addValue(uint64_t /*rowNum*/, common::column_id_t columnIdx,
    std::string_view value) {
    uint64_t length = value.length();
    if (length == 0 && columnIdx == 0) {
        rowEmpty = true;
    } else {
        rowEmpty = false;
    }
    if (columnIdx == reader->getNumColumns() && length == 0) {
        // skip a single trailing delimiter in last columnIdx
        return true;
    }
    currentColumnCount++;
    return true;
}

bool SniffCSVDialectDriver::addRow(uint64_t /*rowNum*/, common::column_id_t /*columnCount*/,
    std::optional<WarningDataWithColumnInfo> /*warningData*/) {
    auto& csvOption = reader->getCSVOption();
    if (rowEmpty) {
        rowEmpty = false;
        if (reader->getNumColumns() != 1) {
            currentColumnCount = 0;
            return false;
        }
        // Otherwise, treat it as null.
    }
    if (resultPosition < csvOption.sampleSize) {
        columnCounts[resultPosition] = currentColumnCount;
        currentColumnCount = 0;
        resultPosition++;
    }
    return true;
}

bool SniffCSVDialectDriver::done(uint64_t rowNum) const {
    auto& csvOption = reader->getCSVOption();
    return (csvOption.hasHeader ? 1 : 0) + csvOption.sampleSize <= rowNum;
}

void SniffCSVDialectDriver::reset() {
    columnCounts = std::vector<idx_t>(columnCounts.size(), 0);
    currentColumnCount = 0;
    error = false;
    resultPosition = 0;
    everQuoted = false;
    everEscaped = false;
}

SniffCSVNameAndTypeDriver::SniffCSVNameAndTypeDriver(SerialCSVReader* reader,
    const function::ExtraScanTableFuncBindInput* bindInput)
    : SerialParsingDriver(getDummyDataChunk(), reader, DriverType::SNIFF_CSV_NAME_AND_TYPE) {
    if (bindInput != nullptr) {
        for (auto i = 0u; i < bindInput->expectedColumnNames.size(); i++) {
            columns.push_back(
                {bindInput->expectedColumnNames[i], bindInput->expectedColumnTypes[i].copy()});
            sniffType.push_back(false);
        }
    }
}

bool SniffCSVNameAndTypeDriver::done(uint64_t rowNum) {
    auto& csvOption = reader->getCSVOption();
    bool finished = (csvOption.hasHeader ? 1 : 0) + csvOption.sampleSize <= rowNum;
    // if the csv only has one row
    if (finished && rowNum <= 1 && csvOption.autoDetection && !csvOption.setHeader) {
        for (auto columnIdx = 0u; columnIdx < firstRow.size(); ++columnIdx) {
            auto value = firstRow[columnIdx];
            if (!utf8proc::Utf8Proc::isValid(value.data(), value.length())) {
                reader->handleCopyException("Invalid UTF8-encoded string.", true /* mustThrow */);
            }
            std::string columnName = std::string(value);
            LogicalType columnType = function::inferMinimalTypeFromString(value);
            columns[columnIdx].first = columnName;
            columns[columnIdx].second = std::move(columnType);
        }
    }
    return finished;
}

bool SniffCSVNameAndTypeDriver::addValue(uint64_t rowNum, common::column_id_t columnIdx,
    std::string_view value) {
    uint64_t length = value.length();
    if (length == 0 && columnIdx == 0) {
        rowEmpty = true;
    } else {
        rowEmpty = false;
    }
    if (columnIdx == reader->getNumColumns() && length == 0) {
        // skip a single trailing delimiter in last columnIdx
        return true;
    }
    auto& csvOption = reader->getCSVOption();
    if (columns.size() < columnIdx + 1 && csvOption.hasHeader && rowNum > 0) {
        reader->handleCopyException(
            stringFormat("expected {} values per row, but got more.", reader->getNumColumns()));
    }
    while (columns.size() < columnIdx + 1) {
        columns.emplace_back(stringFormat("column{}", columns.size()), LogicalType::ANY());
        sniffType.push_back(true);
    }
    if (rowNum == 0 && csvOption.hasHeader) {
        // reading the header
        std::string columnName(value);
        LogicalType columnType(LogicalTypeID::ANY);
        auto it = value.rfind(':');
        if (it != std::string_view::npos) {
            try {
                columnType = LogicalType::convertFromString(std::string(value.substr(it + 1)),
                    reader->getClientContext());
                columnName = std::string(value.substr(0, it));
                sniffType[columnIdx] = false;
            } catch (const Exception&) { // NOLINT(bugprone-empty-catch):
                                         // This is how we check for a suitable
                                         // datatype name.
                // Didn't parse, just use the whole name.
            }
        }
        columns[columnIdx].first = columnName;
        columns[columnIdx].second = std::move(columnType);
    } else if (sniffType[columnIdx] &&
               (rowNum != 0 || !csvOption.autoDetection || csvOption.setHeader)) {
        // reading the body
        LogicalType combinedType;
        columns[columnIdx].second = LogicalTypeUtils::combineTypes(columns[columnIdx].second,
            function::inferMinimalTypeFromString(value));
        if (columns[columnIdx].second.getLogicalTypeID() == LogicalTypeID::STRING) {
            sniffType[columnIdx] = false;
        }
    } else if (sniffType[columnIdx] &&
               (rowNum == 0 && csvOption.autoDetection && !csvOption.setHeader)) {
        // store the first line for later use
        firstRow.push_back(std::string{value});
    }

    return true;
}

SniffCSVHeaderDriver::SniffCSVHeaderDriver(SerialCSVReader* reader,
    const std::vector<std::pair<std::string, common::LogicalType>>& typeDetected)
    : SerialParsingDriver(getDummyDataChunk(), reader, DriverType::SNIFF_CSV_HEADER) {
    for (auto i = 0u; i < typeDetected.size(); i++) {
        columns.push_back({typeDetected[i].first, typeDetected[i].second.copy()});
    }
}

bool SniffCSVHeaderDriver::addValue(uint64_t /*rowNum*/, common::column_id_t columnIdx,
    std::string_view value) {
    uint64_t length = value.length();
    if (length == 0 && columnIdx == 0) {
        rowEmpty = true;
    } else {
        rowEmpty = false;
    }
    if (columnIdx == reader->getNumColumns() && length == 0) {
        // skip a single trailing delimiter in last columnIdx
        return true;
    }

    // reading the header
    LogicalType columnType(LogicalTypeID::ANY);

    columnType = function::inferMinimalTypeFromString(value);

    // Store the value to Header vector for potential later use.
    header.push_back({std::string(value), columnType.copy()});

    // If we already determined has a header, just skip
    if (detectedHeader) {
        return true;
    }

    // If any of the column in the first row cannot be casted to its expected type, we have a
    // header.
    if (columnType.getLogicalTypeID() == LogicalTypeID::STRING &&
        columnType.getLogicalTypeID() != columns[columnIdx].second.getLogicalTypeID() &&
        LogicalTypeID::BLOB != columns[columnIdx].second.getLogicalTypeID() &&
        LogicalTypeID::UNION != columns[columnIdx].second.getLogicalTypeID()) {
        detectedHeader = true;
    }

    return true;
}

} // namespace processor
} // namespace lbug
