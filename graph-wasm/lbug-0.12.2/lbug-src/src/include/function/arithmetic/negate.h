#pragma once

#include <cstdint>

namespace lbug {
namespace function {

struct Negate {
    template<class T>
    static inline void operation(T& input, T& result) {
        result = -input;
    }
};

template<>
void Negate::operation(int8_t& input, int8_t& result);

template<>
void Negate::operation(int16_t& input, int16_t& result);

template<>
void Negate::operation(int32_t& input, int32_t& result);

template<>
void Negate::operation(int64_t& input, int64_t& result);

} // namespace function
} // namespace lbug
