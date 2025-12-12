#pragma once

#include <cstdint>

#include "common/types/int128_t.h"
#include "common/types/uint128_t.h"

namespace lbug {
namespace function {

struct Modulo {
    template<class A, class B, class R>
    static inline void operation(A& left, B& right, R& result) {
        result = fmod(left, right);
    }
};

template<>
void Modulo::operation(uint8_t& left, uint8_t& right, uint8_t& result);

template<>
void Modulo::operation(uint16_t& left, uint16_t& right, uint16_t& result);

template<>
void Modulo::operation(uint32_t& left, uint32_t& right, uint32_t& result);

template<>
void Modulo::operation(uint64_t& left, uint64_t& right, uint64_t& result);

template<>
void Modulo::operation(int8_t& left, int8_t& right, int8_t& result);

template<>
void Modulo::operation(int16_t& left, int16_t& right, int16_t& result);

template<>
void Modulo::operation(int32_t& left, int32_t& right, int32_t& result);

template<>
void Modulo::operation(int64_t& left, int64_t& right, int64_t& result);

template<>
void Modulo::operation(common::int128_t& left, common::int128_t& right, common::int128_t& result);

template<>
void Modulo::operation(common::uint128_t& left, common::uint128_t& right,
    common::uint128_t& result);

} // namespace function
} // namespace lbug
