#pragma once

#include <cstdint>

namespace lbug {
namespace function {

struct Add {
    template<class A, class B, class R>
    static inline void operation(A& left, B& right, R& result) {
        result = left + right;
    }
};

template<>
void Add::operation(uint8_t& left, uint8_t& right, uint8_t& result);

template<>
void Add::operation(uint16_t& left, uint16_t& right, uint16_t& result);

template<>
void Add::operation(uint32_t& left, uint32_t& right, uint32_t& result);

template<>
void Add::operation(uint64_t& left, uint64_t& right, uint64_t& result);

template<>
void Add::operation(int8_t& left, int8_t& right, int8_t& result);

template<>
void Add::operation(int16_t& left, int16_t& right, int16_t& result);

template<>
void Add::operation(int32_t& left, int32_t& right, int32_t& result);

template<>
void Add::operation(int64_t& left, int64_t& right, int64_t& result);

} // namespace function
} // namespace lbug
