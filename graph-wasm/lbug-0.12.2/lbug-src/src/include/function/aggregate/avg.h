#pragma once

#include "common/in_mem_overflow_buffer.h"
#include "common/types/int128_t.h"
#include "common/types/uint128_t.h"
#include "function/aggregate_function.h"
#include "function/arithmetic/add.h"

namespace lbug {
namespace function {

template<typename T>
struct AvgState : public AggregateStateWithNull {
    uint32_t getStateSize() const override { return sizeof(*this); }
    void writeToVector(common::ValueVector* outputVector, uint64_t pos) override {
        outputVector->setValue(pos, avg);
    }

    void finalize()
        requires common::IntegerTypes<T>
    {
        using ResultType = std::conditional<common::SignedIntegerTypes<T>, common::Int128_t,
            common::UInt128_t>::type;
        if (!isNull) {
            avg = ResultType::template cast<long double>(sum) /
                  ResultType::template cast<long double>(count);
        }
    }

    void finalize()
        requires common::FloatingPointTypes<T>
    {
        if (!isNull) {
            avg = sum / count;
        }
    }

    T sum{};
    uint64_t count = 0;
    double avg = 0;
};

template<typename INPUT_TYPE, typename RESULT_TYPE>
struct AvgFunction {

    static std::unique_ptr<AggregateState> initialize() {
        return std::make_unique<AvgState<RESULT_TYPE>>();
    }

    static void updateAll(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        common::InMemOverflowBuffer* /*overflowBuffer*/) {
        auto* state = reinterpret_cast<AvgState<RESULT_TYPE>*>(state_);
        KU_ASSERT(!input->state->isFlat());
        input->forEachNonNull(
            [&](auto pos) { updateSingleValue(state, input, pos, multiplicity); });
    }

    static void updatePos(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        uint32_t pos, common::InMemOverflowBuffer* /*overflowBuffer*/) {
        updateSingleValue(reinterpret_cast<AvgState<RESULT_TYPE>*>(state_), input, pos,
            multiplicity);
    }

    static void updateSingleValue(AvgState<RESULT_TYPE>* state, common::ValueVector* input,
        uint32_t pos, uint64_t multiplicity) {
        INPUT_TYPE val = input->getValue<INPUT_TYPE>(pos);
        for (auto i = 0u; i < multiplicity; ++i) {
            if (state->isNull) {
                state->sum = (RESULT_TYPE)val;
                state->isNull = false;
            } else {
                Add::operation(state->sum, val, state->sum);
            }
        }
        state->count += multiplicity;
    }

    static void combine(uint8_t* state_, uint8_t* otherState_,
        common::InMemOverflowBuffer* /*overflowBuffer*/) {
        auto* otherState = reinterpret_cast<AvgState<RESULT_TYPE>*>(otherState_);
        if (otherState->isNull) {
            return;
        }
        auto* state = reinterpret_cast<AvgState<RESULT_TYPE>*>(state_);
        if (state->isNull) {
            state->sum = otherState->sum;
            state->isNull = false;
        } else {
            Add::operation(state->sum, otherState->sum, state->sum);
        }
        state->count = state->count + otherState->count;
    }

    static void finalize(uint8_t* state_) {
        auto* state = reinterpret_cast<AvgState<RESULT_TYPE>*>(state_);
        state->finalize();
    }
};

} // namespace function
} // namespace lbug
