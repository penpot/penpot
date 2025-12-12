#pragma once

#include "storage/table/column_chunk_data.h"
#include "storage/table/column_chunk_scanner.h"
#include "storage/table/update_info.h"
namespace lbug {
namespace storage {
// Scans all the segments into a single output chunk
struct CombinedChunkScanner : public ColumnChunkScanner {
    explicit CombinedChunkScanner(ColumnChunkData& output)
        : output(output), numValuesBeforeScan(output.getNumValues()) {}

    void scanSegment(common::offset_t offsetInSegment, common::offset_t length,
        scan_func_t scanFunc) override {
        scanFunc(output, offsetInSegment, length);
    }

    void applyCommittedUpdates(const UpdateInfo& updateInfo,
        const transaction::Transaction* transaction, common::offset_t startRow,
        common::offset_t numRows) override {
        updateInfo.scanCommitted(transaction, output, numValuesBeforeScan, startRow, numRows);
    }

    uint64_t getNumValues() override { return output.getNumValues(); }

    ColumnChunkData& output;
    common::offset_t numValuesBeforeScan;
};
} // namespace storage
} // namespace lbug
