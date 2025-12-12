#pragma once

#include <cmath>
#include <cstdint>

#include "numeric_limits.h"

namespace lbug {
namespace function {

template<class SRC, class DST>
inline bool tryCastWithOverflowCheck(SRC value, DST& result) {
    if (NumericLimits<SRC>::isSigned() != NumericLimits<DST>::isSigned()) {
        if (NumericLimits<SRC>::isSigned()) {
            if (NumericLimits<SRC>::maxNumDigits() > NumericLimits<DST>::maxNumDigits()) {
                if (value < 0 || value > (SRC)NumericLimits<DST>::maximum()) {
                    return false;
                }
            } else {
                if (value < 0) {
                    return false;
                }
            }
            result = (DST)value;
            return true;
        } else {
            // unsigned to signed conversion
            if (NumericLimits<SRC>::maxNumDigits() >= NumericLimits<DST>::maxNumDigits()) {
                if (value <= (SRC)NumericLimits<DST>::maximum()) {
                    result = (DST)value;
                    return true;
                }
                return false;
            } else {
                result = (DST)value;
                return true;
            }
        }
    } else {
        // same sign conversion
        if (NumericLimits<DST>::maxNumDigits() >= NumericLimits<SRC>::maxNumDigits()) {
            result = (DST)value;
            return true;
        } else {
            if (value < SRC(NumericLimits<DST>::minimum()) ||
                value > SRC(NumericLimits<DST>::maximum())) {
                return false;
            }
            result = (DST)value;
            return true;
        }
    }
}

template<class SRC, class T>
inline bool tryCastWithOverflowCheckFloat(SRC value, T& result, SRC min, SRC max) {
    if (!(value >= min && value < max)) {
        return false;
    }
    // PG FLOAT => INT casts use statistical rounding.
    result = std::nearbyint(value);
    return true;
}

template<>
inline bool tryCastWithOverflowCheck(float value, int8_t& result) {
    return tryCastWithOverflowCheckFloat<float, int8_t>(value, result, -128.0f, 128.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, int16_t& result) {
    return tryCastWithOverflowCheckFloat<float, int16_t>(value, result, -32768.0f, 32768.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, int32_t& result) {
    return tryCastWithOverflowCheckFloat<float, int32_t>(value, result, -2147483648.0f,
        2147483648.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, int64_t& result) {
    return tryCastWithOverflowCheckFloat<float, int64_t>(value, result, -9223372036854775808.0f,
        9223372036854775808.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, uint8_t& result) {
    return tryCastWithOverflowCheckFloat<float, uint8_t>(value, result, 0.0f, 256.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, uint16_t& result) {
    return tryCastWithOverflowCheckFloat<float, uint16_t>(value, result, 0.0f, 65536.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, uint32_t& result) {
    return tryCastWithOverflowCheckFloat<float, uint32_t>(value, result, 0.0f, 4294967296.0f);
}

template<>
inline bool tryCastWithOverflowCheck(float value, uint64_t& result) {
    return tryCastWithOverflowCheckFloat<float, uint64_t>(value, result, 0.0f,
        18446744073709551616.0f);
}

template<>
inline bool tryCastWithOverflowCheck(double value, int8_t& result) {
    return tryCastWithOverflowCheckFloat<double, int8_t>(value, result, -128.0, 128.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, int16_t& result) {
    return tryCastWithOverflowCheckFloat<double, int16_t>(value, result, -32768.0, 32768.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, int32_t& result) {
    return tryCastWithOverflowCheckFloat<double, int32_t>(value, result, -2147483648.0,
        2147483648.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, int64_t& result) {
    return tryCastWithOverflowCheckFloat<double, int64_t>(value, result, -9223372036854775808.0,
        9223372036854775808.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, uint8_t& result) {
    return tryCastWithOverflowCheckFloat<double, uint8_t>(value, result, 0.0, 256.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, uint16_t& result) {
    return tryCastWithOverflowCheckFloat<double, uint16_t>(value, result, 0.0, 65536.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, uint32_t& result) {
    return tryCastWithOverflowCheckFloat<double, uint32_t>(value, result, 0.0, 4294967296.0);
}

template<>
inline bool tryCastWithOverflowCheck(double value, uint64_t& result) {
    return tryCastWithOverflowCheckFloat<double, uint64_t>(value, result, 0.0,
        18446744073709551615.0);
}

template<>
inline bool tryCastWithOverflowCheck(float input, double& result) {
    result = double(input);
    return true;
}

template<>
inline bool tryCastWithOverflowCheck(double input, float& result) {
    result = float(input);
    return true;
}

} // namespace function
} // namespace lbug
