#pragma once

#include <atomic>
#include <mutex>

#include "common/constants.h"
#include "common/copier_config/file_scan_info.h"
#include "function/table/bind_data.h"
#include "function/table/table_function.h"

namespace lbug {
namespace common {
class FileSystem;
}

namespace function {

struct ScanFileSharedState : public TableFuncSharedState {
    const common::FileScanInfo fileScanInfo;
    std::atomic<uint64_t> fileIdx;
    std::atomic<uint64_t> blockIdx;

    ScanFileSharedState(common::FileScanInfo fileScanInfo, uint64_t numRows)
        : TableFuncSharedState{numRows}, fileScanInfo{std::move(fileScanInfo)}, fileIdx{0},
          blockIdx{0} {}

    std::pair<uint64_t, uint64_t> getNext() {
        std::lock_guard guard{mtx};
        return fileIdx >= fileScanInfo.getNumFiles() ? std::make_pair(UINT64_MAX, UINT64_MAX) :
                                                       std::make_pair(fileIdx.load(), blockIdx++);
    }
};

struct ScanFileWithProgressSharedState : ScanFileSharedState {
    main::ClientContext* context;
    uint64_t totalSize; // TODO(Mattias): I think we should unify the design on how we calculate the
                        // progress bar for scanning. Can we simply rely on a numRowsScaned stored
                        // in the TableFuncSharedState to determine the progress.
    ScanFileWithProgressSharedState(common::FileScanInfo fileScanInfo, uint64_t numRows,
        main::ClientContext* context)
        : ScanFileSharedState{std::move(fileScanInfo), numRows}, context{context}, totalSize{0} {}
};

struct LBUG_API ScanFileBindData : public TableFuncBindData {
    common::FileScanInfo fileScanInfo;
    main::ClientContext* context;
    common::column_id_t numWarningDataColumns = 0;

    ScanFileBindData(binder::expression_vector columns, uint64_t numRows,
        common::FileScanInfo fileScanInfo, main::ClientContext* context)
        : TableFuncBindData{std::move(columns), numRows}, fileScanInfo{std::move(fileScanInfo)},
          context{context} {}
    ScanFileBindData(binder::expression_vector columns, uint64_t numRows,
        common::FileScanInfo fileScanInfo, main::ClientContext* context,
        common::column_id_t numWarningDataColumns)
        : TableFuncBindData{std::move(columns), numRows}, fileScanInfo{std::move(fileScanInfo)},
          context{context}, numWarningDataColumns{numWarningDataColumns} {}
    ScanFileBindData(const ScanFileBindData& other)
        : TableFuncBindData{other}, fileScanInfo{other.fileScanInfo.copy()}, context{other.context},
          numWarningDataColumns{other.numWarningDataColumns} {}

    bool getIgnoreErrorsOption() const override {
        return fileScanInfo.getOption(common::CopyConstants::IGNORE_ERRORS_OPTION_NAME,
            common::CopyConstants::DEFAULT_IGNORE_ERRORS);
    }

    std::unique_ptr<TableFuncBindData> copy() const override {
        return std::make_unique<ScanFileBindData>(*this);
    }
};

} // namespace function
} // namespace lbug
