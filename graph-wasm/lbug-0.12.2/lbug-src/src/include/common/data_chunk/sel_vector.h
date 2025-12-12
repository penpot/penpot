#pragma once

#include <string.h>

#include <memory>

#include "common/types/types.h"
#include <span>

namespace lbug {
namespace common {

class ValueVector;

// A lightweight, immutable view over a SelectionVector, or a subsequence of a selection vector
// SelectionVectors are also SelectionViews so that you can pass a SelectionVector to functions
// which take a SelectionView&
class SelectionView {
protected:
    // In DYNAMIC mode, selectedPositions points to a mutable buffer that can be modified through
    // getMutableBuffer In STATIC mode, selectedPositions points to somewhere in
    // INCREMENTAL_SELECTED_POS
    // Note that the vector is considered unfiltered only if it is both STATIC and the first
    // selected position is 0
    enum class State {
        DYNAMIC,
        STATIC,
    };

public:
    // STATIC selectionView over 0..selectedSize
    explicit SelectionView(sel_t selectedSize);

    template<class Func>
    void forEach(Func&& func) const {
        if (state == State::DYNAMIC) {
            for (size_t i = 0; i < selectedSize; i++) {
                func(selectedPositions[i]);
            }
        } else {
            const auto start = selectedPositions[0];
            for (size_t i = start; i < start + selectedSize; i++) {
                func(i);
            }
        }
    }

    template<class Func>
    void forEachBreakWhenFalse(Func&& func) const {
        if (state == State::DYNAMIC) {
            for (size_t i = 0; i < selectedSize; i++) {
                if (!func(selectedPositions[i])) {
                    break;
                }
            }
        } else {
            const auto start = selectedPositions[0];
            for (size_t i = start; i < start + selectedSize; i++) {
                if (!func(i)) {
                    break;
                }
            }
        }
    }

    sel_t getSelSize() const { return selectedSize; }

    sel_t operator[](sel_t index) const {
        KU_ASSERT(index < selectedSize);
        return selectedPositions[index];
    }

    bool isUnfiltered() const { return state == State::STATIC && selectedPositions[0] == 0; }
    bool isStatic() const { return state == State::STATIC; }

    std::span<const sel_t> getSelectedPositions() const {
        return std::span<const sel_t>(selectedPositions, selectedSize);
    }

protected:
    static SelectionView slice(std::span<const sel_t> selectedPositions, State state) {
        return SelectionView(selectedPositions, state);
    }

    // Intended to be used only as a subsequence of a SelectionVector in SelectionVector::slice
    explicit SelectionView(std::span<const sel_t> selectedPositions, State state)
        : selectedPositions{selectedPositions.data()}, selectedSize{selectedPositions.size()},
          state{state} {}

protected:
    const sel_t* selectedPositions;
    sel_t selectedSize;
    State state;
};

class SelectionVector : public SelectionView {
public:
    explicit SelectionVector(sel_t capacity)
        : SelectionView{std::span<const sel_t>(), State::STATIC},
          selectedPositionsBuffer{std::make_unique<sel_t[]>(capacity)}, capacity{capacity} {
        setToUnfiltered();
    }

    // This View should be considered invalid if the SelectionVector it was created from has been
    // modified
    SelectionView slice(sel_t startIndex, sel_t selectedSize) const {
        return SelectionView::slice(getSelectedPositions().subspan(startIndex, selectedSize),
            state);
    }

    SelectionVector();

    LBUG_API void setToUnfiltered();
    LBUG_API void setToUnfiltered(sel_t size);
    void setRange(sel_t startPos, sel_t size) {
        KU_ASSERT(startPos + size <= capacity);
        selectedPositions = selectedPositionsBuffer.get();
        for (auto i = 0u; i < size; ++i) {
            selectedPositionsBuffer[i] = startPos + i;
        }
        selectedSize = size;
        state = State::DYNAMIC;
    }

    // Set to filtered is not very accurate. It sets selectedPositions to a mutable array.
    void setToFiltered() {
        selectedPositions = selectedPositionsBuffer.get();
        state = State::DYNAMIC;
    }
    void setToFiltered(sel_t size) {
        KU_ASSERT(size <= capacity && selectedPositionsBuffer);
        setToFiltered();
        selectedSize = size;
    }

    // Copies the data in selectedPositions into selectedPositionsBuffer
    void makeDynamic() {
        memcpy(selectedPositionsBuffer.get(), selectedPositions, selectedSize * sizeof(sel_t));
        state = State::DYNAMIC;
        selectedPositions = selectedPositionsBuffer.get();
    }

    std::span<sel_t> getMutableBuffer() const {
        return std::span<sel_t>(selectedPositionsBuffer.get(), capacity);
    }

    void setSelSize(sel_t size) {
        KU_ASSERT(size <= capacity);
        selectedSize = size;
    }
    void incrementSelSize(sel_t increment = 1) {
        KU_ASSERT(selectedSize < capacity);
        selectedSize += increment;
    }

    sel_t operator[](sel_t index) const {
        KU_ASSERT(index < capacity);
        return const_cast<sel_t&>(selectedPositions[index]);
    }
    sel_t& operator[](sel_t index) {
        KU_ASSERT(index < capacity);
        return const_cast<sel_t&>(selectedPositions[index]);
    }

    static std::vector<SelectionVector*> fromValueVectors(
        const std::vector<std::shared_ptr<common::ValueVector>>& vec);

private:
    std::unique_ptr<sel_t[]> selectedPositionsBuffer;
    sel_t capacity;
};

} // namespace common
} // namespace lbug
