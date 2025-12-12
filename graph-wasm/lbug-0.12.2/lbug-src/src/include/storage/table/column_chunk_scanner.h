#pragma once

#include <functional>

#include "common/types/types.h"
namespace lbug {
namespace transaction {
class Transaction;
}
namespace storage {
class ColumnChunkData;
class UpdateInfo;

struct ColumnChunkScanner {
    using scan_func_t = std::function<void(ColumnChunkData& /*outputChunk*/,
        common::offset_t /*offsetInSegment*/, common::offset_t /*length*/)>;

    virtual ~ColumnChunkScanner(){};
    virtual void scanSegment(common::offset_t offsetInSegment, common::offset_t segmentLength,
        scan_func_t scanFunc) = 0;
    virtual void applyCommittedUpdates(const UpdateInfo& updateInfo,
        const transaction::Transaction* transaction, common::offset_t startRow,
        common::offset_t numRows) = 0;
    virtual uint64_t getNumValues() = 0;
};
} // namespace storage
} // namespace lbug
