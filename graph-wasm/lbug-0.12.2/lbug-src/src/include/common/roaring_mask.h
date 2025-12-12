#pragma once

#include "common/mask.h"
#include "roaring.hh"

namespace lbug {
namespace common {

class Roaring32BitmapSemiMask final : public SemiMask {
public:
    explicit Roaring32BitmapSemiMask(offset_t maxOffset)
        : SemiMask(maxOffset), roaring(std::make_shared<roaring::Roaring>()) {}

    void mask(offset_t nodeOffset) override { roaring->add(nodeOffset); }
    void maskRange(offset_t startNodeOffset, offset_t endNodeOffset) override {
        roaring->addRange(startNodeOffset, endNodeOffset);
    }

    bool isMasked(offset_t startNodeOffset) override { return roaring->contains(startNodeOffset); }

    uint64_t getNumMaskedNodes() const override { return roaring->cardinality(); }

    offset_vec_t collectMaskedNodes(uint64_t size) const override;

    // include&exclude
    offset_vec_t range(uint32_t start, uint32_t end) override;

    std::shared_ptr<roaring::Roaring> roaring;
};

class Roaring64BitmapSemiMask final : public SemiMask {
public:
    explicit Roaring64BitmapSemiMask(offset_t maxOffset)
        : SemiMask(maxOffset), roaring(std::make_shared<roaring::Roaring64Map>()) {}

    void mask(offset_t nodeOffset) override { roaring->add(nodeOffset); }
    void maskRange(offset_t startNodeOffset, offset_t endNodeOffset) override {
        roaring->addRange(startNodeOffset, endNodeOffset);
    }

    bool isMasked(offset_t startNodeOffset) override { return roaring->contains(startNodeOffset); }

    uint64_t getNumMaskedNodes() const override { return roaring->cardinality(); }

    offset_vec_t collectMaskedNodes(uint64_t size) const override;

    // include&exclude
    offset_vec_t range(uint32_t start, uint32_t end) override;

    std::shared_ptr<roaring::Roaring64Map> roaring;
};

} // namespace common
} // namespace lbug
