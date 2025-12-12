#pragma once

#include "common/arrow/arrow.h"
#include "common/null_mask.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

class ArrowNullMaskTree {
public:
    ArrowNullMaskTree(const ArrowSchema* schema, const ArrowArray* array, uint64_t srcOffset,
        uint64_t count, const NullMask* parentMask = nullptr);

    void copyToValueVector(ValueVector* vec, uint64_t dstOffset, uint64_t count);
    bool isNull(int64_t idx) { return mask->isNull(idx + offset); }
    ArrowNullMaskTree* getChild(int idx) { return &(*children)[idx]; }
    ArrowNullMaskTree* getDictionary() { return dictionary.get(); }
    ArrowNullMaskTree offsetBy(int64_t offset);

private:
    bool copyFromBuffer(const void* buffer, uint64_t srcOffset, uint64_t count);
    bool applyParentBitmap(const NullMask* buffer);

    template<typename offsetsT>
    void scanListPushDown(const ArrowSchema* schema, const ArrowArray* array, uint64_t srcOffset,
        uint64_t count);

    void scanArrayPushDown(const ArrowSchema* schema, const ArrowArray* array, uint64_t srcOffset,
        uint64_t count);

    void scanStructPushDown(const ArrowSchema* schema, const ArrowArray* array, uint64_t srcOffset,
        uint64_t count);

    int64_t offset;
    std::shared_ptr<NullMask> mask;
    std::shared_ptr<std::vector<ArrowNullMaskTree>> children;
    std::shared_ptr<ArrowNullMaskTree> dictionary;
};

} // namespace common
} // namespace lbug
