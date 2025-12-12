#pragma once

#include "base_csv_reader.h"
#include "common/types/types.h"
#include "function/function.h"
#include "function/table/bind_input.h"
#include "function/table/scan_file_function.h"
#include "function/table/table_function.h"
#include "processor/operator/persistent/reader/file_error_handler.h"

namespace lbug {
namespace processor {

//! ParallelCSVReader is a class that reads values from a stream in parallel.
class ParallelCSVReader final : public BaseCSVReader {
    friend class ParallelParsingDriver;

public:
    ParallelCSVReader(const std::string& filePath, common::idx_t fileIdx, common::CSVOption option,
        CSVColumnInfo columnInfo, main::ClientContext* context,
        LocalFileErrorHandler* errorHandler);

    bool hasMoreToRead() const;
    uint64_t parseBlock(common::block_idx_t blockIdx, common::DataChunk& resultChunk) override;
    uint64_t continueBlock(common::DataChunk& resultChunk);

    void reportFinishedBlock();

protected:
    bool handleQuotedNewline() override;

private:
    bool finishedBlock() const;
    void seekToBlockStart();
};

struct ParallelCSVLocalState final : public function::TableFuncLocalState {
    std::unique_ptr<ParallelCSVReader> reader;
    std::unique_ptr<LocalFileErrorHandler> errorHandler;
    common::idx_t fileIdx = common::INVALID_IDX;
};

struct ParallelCSVScanSharedState final : public function::ScanFileWithProgressSharedState {
    common::CSVOption csvOption;
    CSVColumnInfo columnInfo;
    std::atomic<uint64_t> numBlocksReadByFiles = 0;
    std::vector<SharedFileErrorHandler> errorHandlers;
    populate_func_t populateErrorFunc;

    ParallelCSVScanSharedState(common::FileScanInfo fileScanInfo, uint64_t numRows,
        main::ClientContext* context, common::CSVOption csvOption, CSVColumnInfo columnInfo);

    void setFileComplete(uint64_t completedFileIdx);
    populate_func_t constructPopulateFunc();
};

struct ParallelCSVScan {
    static constexpr const char* name = "READ_CSV_PARALLEL";

    static function::function_set getFunctionSet();
};

} // namespace processor
} // namespace lbug
