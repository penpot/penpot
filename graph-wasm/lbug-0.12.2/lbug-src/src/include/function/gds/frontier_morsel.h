#pragma once

#include <atomic>

#include "common/types/types.h"

namespace lbug {
namespace function {

class FrontierMorsel {
public:
    FrontierMorsel() = default;

    common::offset_t getBeginOffset() const { return beginOffset; }
    common::offset_t getEndOffset() const { return endOffset; }

    void init(common::offset_t beginOffset_, common::offset_t endOffset_) {
        beginOffset = beginOffset_;
        endOffset = endOffset_;
    }

private:
    common::offset_t beginOffset = common::INVALID_OFFSET;
    common::offset_t endOffset = common::INVALID_OFFSET;
};

class LBUG_API FrontierMorselDispatcher {
    static constexpr uint64_t MIN_FRONTIER_MORSEL_SIZE = 512;
    // Note: MIN_NUMBER_OF_FRONTIER_MORSELS is the minimum number of morsels we aim to have but we
    // can have fewer than this. See the beginFrontierComputeBetweenTables to see the actual
    // morselSize computation for details.
    static constexpr uint64_t MIN_NUMBER_OF_FRONTIER_MORSELS = 128;

public:
    explicit FrontierMorselDispatcher(uint64_t maxThreads);

    void init(common::offset_t _maxOffset);

    bool getNextRangeMorsel(FrontierMorsel& frontierMorsel);

private:
    common::offset_t maxOffset;
    std::atomic<common::offset_t> nextOffset;
    uint64_t maxThreads;
    uint64_t morselSize;
};

} // namespace function
} // namespace lbug
