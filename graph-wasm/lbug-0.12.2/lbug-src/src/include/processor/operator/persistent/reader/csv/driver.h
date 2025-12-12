#pragma once

#include <cstdint>
#include <optional>

#include "common/data_chunk/data_chunk.h"
#include "function/table/bind_input.h"
#include "processor/operator/persistent/reader/copy_from_error.h"

namespace lbug {
namespace main {
class ClientContext;
}

namespace processor {

// TODO(Keenan): Split up this file.
class BaseCSVReader;

// Driver type identifications.
enum class DriverType {
    PARSING,
    PARALLEL,
    SERIAL,
    SNIFF_CSV_DIALECT,
    SNIFF_CSV_NAME_AND_TYPE,
    SNIFF_CSV_HEADER,
    HEADER,
    SKIP_ROW
};

struct WarningDataWithColumnInfo {
    WarningDataWithColumnInfo(const WarningSourceData& warningSourceData,
        uint64_t warningDataStartColumnIdx)
        : warningDataStartColumnIdx(warningDataStartColumnIdx), data(warningSourceData) {}

    uint64_t warningDataStartColumnIdx;
    WarningSourceData data;
};

class ParsingDriver {
public:
    explicit ParsingDriver(common::DataChunk& chunk, DriverType type = DriverType::PARSING);
    virtual ~ParsingDriver() = default;

    bool done(uint64_t rowNum);
    virtual bool addValue(uint64_t rowNum, common::column_id_t columnIdx, std::string_view value);
    virtual bool addRow(uint64_t rowNum, common::column_id_t columnCount,
        std::optional<WarningDataWithColumnInfo> warningData);

public:
    const DriverType driverType;

private:
    virtual bool doneEarly() = 0;
    virtual BaseCSVReader* getReader() = 0;

private:
    common::DataChunk& chunk;

protected:
    bool rowEmpty;
};

class ParallelCSVReader;

class ParallelParsingDriver : public ParsingDriver {
public:
    ParallelParsingDriver(common::DataChunk& chunk, ParallelCSVReader* reader);
    bool doneEarly() override;

private:
    BaseCSVReader* getReader() override;

private:
    ParallelCSVReader* reader;
};

class SerialCSVReader;

class SerialParsingDriver : public ParsingDriver {
public:
    SerialParsingDriver(common::DataChunk& chunk, SerialCSVReader* reader,
        DriverType type = DriverType::SERIAL);
    bool doneEarly() override;

private:
    BaseCSVReader* getReader() override;

protected:
    SerialCSVReader* reader;
};

class SniffCSVDialectDriver : public SerialParsingDriver {
public:
    explicit SniffCSVDialectDriver(SerialCSVReader* reader);

    bool done(uint64_t rowNum) const;
    bool addValue(uint64_t rowNum, common::column_id_t columnIdx, std::string_view value) override;
    bool addRow(uint64_t rowNum, common::column_id_t columnCount,
        std::optional<WarningDataWithColumnInfo> warningData) override;
    void reset();

    void setEverQuoted() { everQuoted = true; }
    void setEverEscaped() { everEscaped = true; }
    void setError() { error = true; }

    bool getEverQuoted() const { return everQuoted; }
    bool getEverEscaped() const { return everEscaped; }
    bool getError() const { return error; }
    common::idx_t getResultPosition() const { return resultPosition; }
    common::idx_t getColumnCount(common::idx_t index) const { return columnCounts[index]; }

private:
    std::vector<common::idx_t> columnCounts;
    common::idx_t currentColumnCount = 0;
    bool error = false;
    common::idx_t resultPosition = 0;
    bool everQuoted = false;
    bool everEscaped = false;
};

class SniffCSVNameAndTypeDriver : public SerialParsingDriver {
public:
    SniffCSVNameAndTypeDriver(SerialCSVReader* reader,
        const function::ExtraScanTableFuncBindInput* bindInput);

    bool done(uint64_t rowNum);
    bool addValue(uint64_t rowNum, common::column_id_t columnIdx, std::string_view value) override;

public:
    std::vector<std::string> firstRow;
    std::vector<std::pair<std::string, common::LogicalType>> columns;
    std::vector<bool> sniffType;
    // if the type isn't declared in the header, sniff it
};

class SniffCSVHeaderDriver : public SerialParsingDriver {
public:
    SniffCSVHeaderDriver(SerialCSVReader* reader,
        const std::vector<std::pair<std::string, common::LogicalType>>& TypeDetected);

    bool done(uint64_t rowNum) const {
        // Only read the first line.
        return (0 < rowNum);
    };

    bool addValue(uint64_t rowNum, common::column_id_t columnIdx, std::string_view value) override;

public:
    std::vector<std::pair<std::string, common::LogicalType>> columns;
    std::vector<std::pair<std::string, common::LogicalType>> header;
    bool detectedHeader = false;
};

} // namespace processor
} // namespace lbug
