#include "common/data_chunk/sel_vector.h"

#include <array>
#include <numeric>

#include "common/system_config.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

// NOLINTNEXTLINE(cert-err58-cpp): always evaluated at compile time, and even not it would not throw
static const std::array<sel_t, DEFAULT_VECTOR_CAPACITY> INCREMENTAL_SELECTED_POS =
    []() constexpr noexcept {
        std::array<sel_t, DEFAULT_VECTOR_CAPACITY> selectedPos{};
        std::iota(selectedPos.begin(), selectedPos.end(), 0);
        return selectedPos;
    }();

SelectionView::SelectionView(sel_t selectedSize)
    : selectedPositions{INCREMENTAL_SELECTED_POS.data()}, selectedSize{selectedSize},
      state{State::STATIC} {}

SelectionVector::SelectionVector() : SelectionVector{DEFAULT_VECTOR_CAPACITY} {}

void SelectionVector::setToUnfiltered() {
    selectedPositions = INCREMENTAL_SELECTED_POS.data();
    state = State::STATIC;
}
void SelectionVector::setToUnfiltered(sel_t size) {
    KU_ASSERT(size <= capacity);
    selectedPositions = INCREMENTAL_SELECTED_POS.data();
    selectedSize = size;
    state = State::STATIC;
}

std::vector<SelectionVector*> SelectionVector::fromValueVectors(
    const std::vector<std::shared_ptr<ValueVector>>& vec) {
    std::vector<SelectionVector*> ret(vec.size());
    for (size_t i = 0; i < vec.size(); ++i) {
        ret[i] = vec[i]->getSelVectorPtr();
    }
    return ret;
}

} // namespace common
} // namespace lbug
