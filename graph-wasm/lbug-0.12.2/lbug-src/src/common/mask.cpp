#include "common/mask.h"

#include "common/roaring_mask.h"

namespace lbug {
namespace common {

std::unique_ptr<SemiMask> SemiMaskUtil::createMask(offset_t maxOffset) {
    if (maxOffset > std::numeric_limits<uint32_t>::max()) {
        return std::make_unique<Roaring64BitmapSemiMask>(maxOffset);
    }
    return std::make_unique<Roaring32BitmapSemiMask>(maxOffset);
}

offset_t NodeOffsetMaskMap::getNumMaskedNode() const {
    offset_t numNodes = 0;
    for (auto& [tableID, mask] : maskMap) {
        numNodes += mask->getNumMaskedNodes();
    }
    return numNodes;
}

} // namespace common
} // namespace lbug
