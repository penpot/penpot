#pragma once

#include "function/aggregate_function.h"

namespace lbug {
namespace function {

struct BaseCountFunction {

    struct CountState : public AggregateState {
        inline uint32_t getStateSize() const override { return sizeof(*this); }
        inline void writeToVector(common::ValueVector* outputVector, uint64_t pos) override {
            memcpy(outputVector->getData() + pos * outputVector->getNumBytesPerValue(),
                reinterpret_cast<uint8_t*>(&count), outputVector->getNumBytesPerValue());
        }

        uint64_t count = 0;
    };

    static std::unique_ptr<AggregateState> initialize() {
        auto state = std::make_unique<CountState>();
        return state;
    }

    static void combine(uint8_t* state_, uint8_t* otherState_,
        common::InMemOverflowBuffer* /*overflowBuffer*/) {
        auto state = reinterpret_cast<CountState*>(state_);
        auto otherState = reinterpret_cast<CountState*>(otherState_);
        state->count += otherState->count;
    }

    static void finalize(uint8_t* /*state_*/) {}
};

} // namespace function
} // namespace lbug
