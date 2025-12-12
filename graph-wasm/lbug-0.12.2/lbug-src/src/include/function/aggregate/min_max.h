#pragma once

#include "common/in_mem_overflow_buffer.h"
#include "function/aggregate_function.h"

namespace lbug {
namespace function {

template<typename T>
struct MinMaxFunction {

    struct MinMaxState : public AggregateStateWithNull {
        uint32_t getStateSize() const override { return sizeof(*this); }
        void writeToVector(common::ValueVector* outputVector, uint64_t pos) override {
            outputVector->setValue(pos, val);
        }
        void setVal(const T& val_, common::InMemOverflowBuffer* /*overflowBuffer*/) { val = val_; }

        T val{};
    };

    static std::unique_ptr<AggregateState> initialize() { return std::make_unique<MinMaxState>(); }

    template<class OP>
    static void updateAll(uint8_t* state_, common::ValueVector* input, uint64_t /*multiplicity*/,
        common::InMemOverflowBuffer* overflowBuffer) {
        KU_ASSERT(!input->state->isFlat());
        auto* state = reinterpret_cast<MinMaxState*>(state_);
        input->forEachNonNull(
            [&](auto pos) { updateSingleValue<OP>(state, input, pos, overflowBuffer); });
    }

    template<class OP>
    static inline void updatePos(uint8_t* state_, common::ValueVector* input,
        uint64_t /*multiplicity*/, uint32_t pos, common::InMemOverflowBuffer* overflowBuffer) {
        updateSingleValue<OP>(reinterpret_cast<MinMaxState*>(state_), input, pos, overflowBuffer);
    }

    template<class OP>
    static void updateSingleValue(MinMaxState* state, common::ValueVector* input, uint32_t pos,
        common::InMemOverflowBuffer* overflowBuffer) {
        T val = input->getValue<T>(pos);
        if (state->isNull) {
            state->setVal(val, overflowBuffer);
            state->isNull = false;
        } else {
            uint8_t compare_result = 0;
            OP::template operation<T, T>(val, state->val, compare_result, nullptr /* leftVector */,
                nullptr /* rightVector */);
            if (compare_result) {
                state->setVal(val, overflowBuffer);
            }
        }
    }

    template<class OP>
    static void combine(uint8_t* state_, uint8_t* otherState_,
        common::InMemOverflowBuffer* overflowBuffer) {
        auto* otherState = reinterpret_cast<MinMaxState*>(otherState_);
        if (otherState->isNull) {
            return;
        }
        auto* state = reinterpret_cast<MinMaxState*>(state_);
        if (state->isNull) {
            state->setVal(otherState->val, overflowBuffer);
            state->isNull = false;
        } else {
            uint8_t compareResult = 0;
            OP::template operation<T, T>(otherState->val, state->val, compareResult,
                nullptr /* leftVector */, nullptr /* rightVector */);
            if (compareResult) {
                state->setVal(otherState->val, overflowBuffer);
            }
        }
    }

    static void finalize(uint8_t* /*state_*/) {}
};

template<>
void MinMaxFunction<common::ku_string_t>::MinMaxState::setVal(const common::ku_string_t& val_,
    common::InMemOverflowBuffer* overflowBuffer) {
    // We only need to allocate memory if the new val_ is a long string and is longer
    // than the current val.
    if (val_.len > common::ku_string_t::SHORT_STR_LENGTH && val_.len > val.len) {
        val.overflowPtr = reinterpret_cast<uint64_t>(overflowBuffer->allocateSpace(val_.len));
    }
    val.set(val_);
}

} // namespace function
} // namespace lbug
