#pragma once

#include "function/aggregate_function.h"
#include "function/arithmetic/add.h"

namespace lbug {
namespace function {

template<typename RESULT_TYPE>
struct SumState : public AggregateStateWithNull {
    uint32_t getStateSize() const override { return sizeof(*this); }
    void writeToVector(common::ValueVector* outputVector, uint64_t pos) override {
        outputVector->setValue(pos, sum);
    }

    RESULT_TYPE sum{};
};

template<typename INPUT_TYPE, typename RESULT_TYPE>
struct SumFunction {
    static std::unique_ptr<AggregateState> initialize() {
        return std::make_unique<SumState<RESULT_TYPE>>();
    }

    static void updateAll(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        common::InMemOverflowBuffer* /*overflowBuffer*/) {
        KU_ASSERT(!input->state->isFlat());
        auto* state = reinterpret_cast<SumState<RESULT_TYPE>*>(state_);
        input->forEachNonNull(
            [&](auto pos) { updateSingleValue(state, input, pos, multiplicity); });
    }

    static void updatePos(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        uint32_t pos, common::InMemOverflowBuffer* /*overflowBuffer*/) {
        auto* state = reinterpret_cast<SumState<RESULT_TYPE>*>(state_);
        updateSingleValue(state, input, pos, multiplicity);
    }

    static void updateSingleValue(SumState<RESULT_TYPE>* state, common::ValueVector* input,
        uint32_t pos, uint64_t multiplicity) {
        INPUT_TYPE val = input->getValue<INPUT_TYPE>(pos);
        for (auto j = 0u; j < multiplicity; ++j) {
            if (state->isNull) {
                state->sum = val;
                state->isNull = false;
            } else {
                Add::operation(state->sum, val, state->sum);
            }
        }
    }

    static void combine(uint8_t* state_, uint8_t* otherState_,
        common::InMemOverflowBuffer* /*overflowBuffer*/) {
        auto* otherState = reinterpret_cast<SumState<RESULT_TYPE>*>(otherState_);
        if (otherState->isNull) {
            return;
        }
        auto* state = reinterpret_cast<SumState<RESULT_TYPE>*>(state_);
        if (state->isNull) {
            state->sum = otherState->sum;
            state->isNull = false;
        } else {
            Add::operation(state->sum, otherState->sum, state->sum);
        }
    }

    static void finalize(uint8_t* /*state_*/) {}
};

} // namespace function
} // namespace lbug
