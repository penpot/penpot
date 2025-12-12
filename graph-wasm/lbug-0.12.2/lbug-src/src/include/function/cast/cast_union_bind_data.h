#pragma once

#include "common/data_chunk/sel_vector.h"
#include "common/types/types.h"
#include "function/function.h"

namespace lbug {
namespace function {

struct CastToUnionBindData : public FunctionBindData {
    using inner_func_t = std::function<void(common::ValueVector*, common::ValueVector&,
        common::SelectionVector*, uint64_t inputPos, uint64_t resultPos)>;

    common::union_field_idx_t targetTag;
    inner_func_t innerFunc;

    CastToUnionBindData(common::union_field_idx_t targetTag, inner_func_t innerFunc,
        common::LogicalType dataType)
        : FunctionBindData{std::move(dataType)}, targetTag{targetTag},
          innerFunc{std::move(innerFunc)} {}

    std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<CastToUnionBindData>(targetTag, innerFunc, resultType.copy());
    }
};

} // namespace function
} // namespace lbug
