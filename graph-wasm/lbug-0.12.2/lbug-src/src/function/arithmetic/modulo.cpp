#include "function/arithmetic/modulo.h"

#include "common/exception/overflow.h"
#include "common/exception/runtime.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB multiply.cpp
template<class SRC_TYPE, class DST_TYPE>
static inline bool tryModuloWithOverflowCheck(SRC_TYPE left, SRC_TYPE right, SRC_TYPE& result) {
    DST_TYPE uresult;
    if (left == NumericLimits<SRC_TYPE>::minimum() && right == -1) {
        return false;
    }
    uresult = static_cast<DST_TYPE>(left) % static_cast<DST_TYPE>(right);
    result = static_cast<SRC_TYPE>(uresult);
    return true;
}

struct TryModulo {
    template<class A, class B, class R>
    static inline bool operation(A& left, B& right, R& result);
};

template<>
bool inline TryModulo::operation(int8_t& left, int8_t& right, int8_t& result) {
    return tryModuloWithOverflowCheck<int8_t, int16_t>(left, right, result);
}

template<>
bool inline TryModulo::operation(int16_t& left, int16_t& right, int16_t& result) {
    return tryModuloWithOverflowCheck<int16_t, int32_t>(left, right, result);
}

template<>
bool inline TryModulo::operation(int32_t& left, int32_t& right, int32_t& result) {
    return tryModuloWithOverflowCheck<int32_t, int64_t>(left, right, result);
}

template<>
bool TryModulo::operation(int64_t& left, int64_t& right, int64_t& result) {
    if (left == NumericLimits<int64_t>::minimum() && right == -1) {
        return false;
    }
    return tryModuloWithOverflowCheck<int64_t, int64_t>(left, right, result);
}

template<>
void Modulo::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    result = left % right;
}

template<>
void Modulo::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    result = left % right;
}

template<>
void Modulo::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    result = left % right;
}

template<>
void Modulo::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    result = left % right;
}

template<>
void Modulo::operation(int8_t& left, int8_t& right, int8_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    if (!TryModulo::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} % {} is not within INT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Modulo::operation(int16_t& left, int16_t& right, int16_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    if (!TryModulo::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} % {} is not within INT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Modulo::operation(int32_t& left, int32_t& right, int32_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    if (!TryModulo::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} % {} is not within INT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Modulo::operation(int64_t& left, int64_t& right, int64_t& result) {
    if (right == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    if (!TryModulo::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} % {} is not within INT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Modulo::operation(common::int128_t& left, common::int128_t& right, common::int128_t& result) {
    result = left % right;
}

template<>
void Modulo::operation(common::uint128_t& left, common::uint128_t& right,
    common::uint128_t& result) {
    result = left % right;
}

} // namespace function
} // namespace lbug
