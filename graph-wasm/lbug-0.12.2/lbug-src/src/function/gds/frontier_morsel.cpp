#include "function/gds/frontier_morsel.h"

using namespace lbug::common;

namespace lbug {
namespace function {

FrontierMorselDispatcher::FrontierMorselDispatcher(uint64_t maxThreads)
    : maxOffset{INVALID_OFFSET}, maxThreads{maxThreads}, morselSize(UINT64_MAX) {
    nextOffset.store(INVALID_OFFSET);
}

void FrontierMorselDispatcher::init(offset_t _maxOffset) {
    maxOffset = _maxOffset;
    nextOffset.store(0u);
    // Frontier size calculation: The ideal scenario is to have k^2 many morsels where k
    // the number of maximum threads that could be working on this frontier. However, if
    // that is too small then we default to MIN_FRONTIER_MORSEL_SIZE.
    auto idealMorselSize =
        maxOffset / std::max(MIN_NUMBER_OF_FRONTIER_MORSELS, maxThreads * maxThreads);
    morselSize = std::max(MIN_FRONTIER_MORSEL_SIZE, idealMorselSize);
}

bool FrontierMorselDispatcher::getNextRangeMorsel(FrontierMorsel& frontierMorsel) {
    auto beginOffset = nextOffset.fetch_add(morselSize, std::memory_order_acq_rel);
    if (beginOffset >= maxOffset) {
        return false;
    }
    auto endOffset = beginOffset + morselSize > maxOffset ? maxOffset : beginOffset + morselSize;
    frontierMorsel.init(beginOffset, endOffset);
    return true;
}

} // namespace function
} // namespace lbug
