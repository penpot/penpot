#pragma once

#include "storage/table/column_chunk.h"
#include "storage/table/column_chunk_scanner.h"
namespace lbug {
namespace storage {
struct LazySegmentData {
    std::unique_ptr<ColumnChunkData> segmentData;
    common::offset_t startOffsetInSegment;
    common::offset_t length;
    ColumnChunkScanner::scan_func_t scanFunc;

    // Used for genericRangeSegments()
    const LazySegmentData& operator*() const { return *this; }
    common::offset_t getNumValues() const { return length; }
};

// Separately scans each segment in a column chunk
// Avoids scanning a segment unless a call to updateScannedValue() is made for the current segment
class LazySegmentScanner : public ColumnChunkScanner {
public:
    LazySegmentScanner(MemoryManager& mm, common::LogicalType columnType, bool enableCompression)
        : numValues(0), mm(mm), columnType(std::move(columnType)),
          enableCompression(enableCompression) {}

    struct Iterator {
        common::offset_t segmentIdx;
        common::offset_t offsetInSegment;
        LazySegmentScanner& segmentScanner;

        void advance(common::offset_t n);
        void operator++() { advance(1); }
        LazySegmentData& operator*() const;
        LazySegmentData* operator->() const { return &*(*this); }
    };

    Iterator begin() { return Iterator{0, 0, *this}; }

    // Since we lazily scan segments
    // This actually only adds the information needed to scan the segment
    // Either updateScannedValue() or scanSegmentIfNeeded must be called to actually scan
    void scanSegment(common::offset_t offsetInSegment, common::offset_t segmentLength,
        scan_func_t newScanFunc) override;

    void applyCommittedUpdates(const UpdateInfo& updateInfo,
        const transaction::Transaction* transaction, common::offset_t startRow,
        common::offset_t numRows) override;

    uint64_t getNumValues() override { return numValues; }

    void scanSegmentIfNeeded(LazySegmentData& segment);
    void scanSegmentIfNeeded(common::idx_t segmentIdx) {
        scanSegmentIfNeeded(segments[segmentIdx]);
    }

    template<std::invocable<LazySegmentData&, common::offset_t /*offsetInSegment*/,
        common::offset_t /*lengthInSegment*/, common::offset_t /*dstOffset*/>
            Func>
    void rangeSegments(Iterator startIt, common::length_t length, Func func);

private:
    std::vector<LazySegmentData> segments;

    uint64_t numValues;

    MemoryManager& mm;
    common::LogicalType columnType;
    bool enableCompression;
};

inline LazySegmentData& LazySegmentScanner::Iterator::operator*() const {
    KU_ASSERT(segmentIdx < segmentScanner.segments.size() &&
              offsetInSegment < segmentScanner.segments[segmentIdx].length);
    return segmentScanner.segments[segmentIdx];
}

template<
    std::invocable<LazySegmentData&, common::offset_t, common::offset_t, common::offset_t> Func>
void LazySegmentScanner::rangeSegments(Iterator startIt, common::length_t length, Func func) {
    auto segmentSpan = std::span(segments);
    genericRangeSegmentsFromIt(segmentSpan, segmentSpan.begin() + startIt.segmentIdx,
        startIt.offsetInSegment, length, func);
}

} // namespace storage
} // namespace lbug
