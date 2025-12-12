#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct PointerFunctionExecutor {
    template<typename RESULT_TYPE, typename OP>
    static void execute(common::ValueVector& result, common::SelectionVector& sel, void* dataPtr) {
        if (sel.isUnfiltered()) {
            for (auto i = 0u; i < sel.getSelSize(); i++) {
                OP::operation(result.getValue<RESULT_TYPE>(i), dataPtr);
            }
        } else {
            for (auto i = 0u; i < sel.getSelSize(); i++) {
                auto pos = sel[i];
                OP::operation(result.getValue<RESULT_TYPE>(pos), dataPtr);
            }
        }
    }
};

} // namespace function
} // namespace lbug
