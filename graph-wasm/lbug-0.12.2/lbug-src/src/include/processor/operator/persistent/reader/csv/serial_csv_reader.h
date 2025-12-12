#pragma once

#include "base_csv_reader.h"
#include "function/function.h"
#include "function/table/bind_input.h"
#include "function/table/scan_file_function.h"
#include "processor/operator/persistent/reader/csv/dialect_detection.h"
#include "processor/operator/persistent/reader/file_error_handler.h"

namespace lbug {
namespace processor {

//! Serial CSV reader is a class that reads values from a stream in a single thread.
class SerialCSVReader final : public BaseCSVReader {
public:
    SerialCSVReader(const std::string& filePath, common::idx_t fileIdx, common::CSVOption option,
        CSVColumnInfo columnInfo, main::ClientContext* context, LocalFileErrorHandler* errorHandler,
        const function::ExtraScanTableFuncBindInput* bindInput = nullptr);

    //! Sniffs CSV dialect and determines skip rows, header row, column types and column names
    std::vector<std::pair<std::string, common::LogicalType>> sniffCSV(
        DialectOption& detectedDialect, bool& detectedHeader);
    uint64_t parseBlock(common::block_idx_t blockIdx, common::DataChunk& resultChunk) override;

protected:
    bool handleQuotedNewline() override { return true; }

private:
    const function::ExtraScanTableFuncBindInput* bindInput;
    void resetReaderState();
    DialectOption detectDialect();
    bool detectHeader(std::vector<std::pair<std::string, common::LogicalType>>& detectedTypes);
};

struct SerialCSVScanSharedState final : public function::ScanFileWithProgressSharedState {
    std::unique_ptr<SerialCSVReader> reader;
    common::CSVOption csvOption;
    CSVColumnInfo columnInfo;
    uint64_t totalReadSizeByFile;
    std::unique_ptr<SharedFileErrorHandler> sharedErrorHandler;
    std::unique_ptr<LocalFileErrorHandler> localErrorHandler;
    uint64_t queryID;
    populate_func_t populateErrorFunc;

    SerialCSVScanSharedState(common::FileScanInfo fileScanInfo, uint64_t numRows,
        main::ClientContext* context, common::CSVOption csvOption, CSVColumnInfo columnInfo,
        uint64_t queryID);

    void read(common::DataChunk& outputChunk);

    void initReader(main::ClientContext* context);
    void finalizeReader(main::ClientContext* context) const;

    populate_func_t constructPopulateFunc() const;
};

struct SerialCSVScan {
    static constexpr const char* name = "READ_CSV_SERIAL";

    static function::function_set getFunctionSet();
    static void bindColumns(const function::ExtraScanTableFuncBindInput* bindInput,
        std::vector<std::string>& columnNames, std::vector<common::LogicalType>& columnTypes,
        DialectOption& detectedDialect, bool& detectedHeader, main::ClientContext* context);
};

} // namespace processor
} // namespace lbug
