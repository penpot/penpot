#pragma once

#include <string>
#include <type_traits>

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "common/types/int128_t.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "function/cast/functions/cast_string_non_nested_functions.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

template<typename A, typename B>
struct pickDecimalPhysicalType {
    static constexpr bool AISFLOAT = std::is_floating_point<A>::value;
    static constexpr bool BISFLOAT = std::is_floating_point<B>::value;
    using RES =
        typename std::conditional<(AISFLOAT ? false : (BISFLOAT ? true : sizeof(A) > sizeof(B))), A,
            B>::type;
};

struct CastDecimalTo {
    template<typename SRC, typename DST>
    static void operation(SRC& input, DST& output, const ValueVector& inputVec,
        ValueVector& outputVec) {
        using T = typename pickDecimalPhysicalType<SRC, DST>::RES;
        constexpr auto pow10s = pow10Sequence<T>();
        auto scale = DecimalType::getScale(inputVec.dataType);
        if constexpr (std::is_floating_point<DST>::value) {
            output = (DST)input / (DST)pow10s[scale];
        } else {
            auto roundconst = (input < 0 ? -5 : 5);
            auto tmp = ((scale > 0 ? pow10s[scale - 1] * roundconst : 0) + input) / pow10s[scale];
            if (tmp < NumericLimits<DST>::minimum() || tmp > NumericLimits<DST>::maximum()) {
                throw OverflowException(stringFormat("Cast Failed: {} is not in {} range",
                    DecimalType::insertDecimalPoint(TypeUtils::toString(input), scale),
                    outputVec.dataType.toString()));
            }
            output = (DST)tmp;
        }
    }
};

struct CastToDecimal {
    template<typename SRC, typename DST>
    static void operation(SRC& input, DST& output, const ValueVector&,
        const ValueVector& outputVec) {
        constexpr auto pow10s = pow10Sequence<DST>();
        auto precision = DecimalType::getPrecision(outputVec.dataType);
        auto scale = DecimalType::getScale(outputVec.dataType);
        if constexpr (std::is_floating_point<SRC>::value) {
            auto roundconst = (input < 0 ? -0.5 : 0.5);
            output = (DST)((double)pow10s[scale] * input + roundconst);
        } else {
            output = (DST)(pow10s[scale] * input);
        }
        if (output <= -pow10s[precision] || output >= pow10s[precision]) {
            throw OverflowException(stringFormat("To Decimal Cast Failed: {} is not in {} range",
                TypeUtils::toString(input), outputVec.dataType.toString()));
        }
    }
};

struct CastBetweenDecimal {
    template<typename SRC, typename DST>
    static void operation(SRC& input, DST& output, const ValueVector& inputVec,
        const ValueVector& outputVec) {
        using T = typename pickDecimalPhysicalType<SRC, DST>::RES;
        constexpr auto pow10s = pow10Sequence<T>();
        auto outputPrecision = DecimalType::getPrecision(outputVec.dataType);
        auto inputScale = DecimalType::getScale(inputVec.dataType);
        auto outputScale = DecimalType::getScale(outputVec.dataType);
        if (inputScale == outputScale) {
            output = (DST)input;
        } else if (inputScale < outputScale) {
            output = (DST)(pow10s[outputScale - inputScale] * input);
        } else {
            auto roundconst = (input < 0 ? -5 : 5);
            output = (DST)((pow10s[inputScale - outputScale - 1] * roundconst + input) /
                           pow10s[inputScale - outputScale]);
        }
        if (pow10s[outputPrecision] <= output || -pow10s[outputPrecision] >= output) {
            throw OverflowException(stringFormat(
                "Decimal Cast Failed: input {} is not in range of {}",
                DecimalType::insertDecimalPoint(TypeUtils::toString(input, nullptr), inputScale),
                outputVec.dataType.toString()));
        }
    }
};

// DECIMAL TO STRING SPECIALIZATION
template<>
inline void CastDecimalTo::operation(int16_t& input, ku_string_t& output,
    const ValueVector& inputVec, ValueVector& resultVector) {
    auto scale = DecimalType::getScale(inputVec.dataType);
    auto str = DecimalType::insertDecimalPoint(std::to_string(input), scale);
    common::StringVector::addString(&resultVector, output, str);
}

template<>
inline void CastDecimalTo::operation(int32_t& input, ku_string_t& output,
    const ValueVector& inputVec, ValueVector& resultVector) {
    auto scale = DecimalType::getScale(inputVec.dataType);
    auto str = DecimalType::insertDecimalPoint(std::to_string(input), scale);
    common::StringVector::addString(&resultVector, output, str);
}

template<>
inline void CastDecimalTo::operation(int64_t& input, ku_string_t& output,
    const ValueVector& inputVec, ValueVector& resultVector) {
    auto scale = DecimalType::getScale(inputVec.dataType);
    auto str = DecimalType::insertDecimalPoint(std::to_string(input), scale);
    common::StringVector::addString(&resultVector, output, str);
}

template<>
inline void CastDecimalTo::operation(common::int128_t& input, ku_string_t& output,
    const ValueVector& inputVec, ValueVector& resultVector) {
    auto scale = DecimalType::getScale(inputVec.dataType);
    auto str = DecimalType::insertDecimalPoint(common::Int128_t::toString(input), scale);
    common::StringVector::addString(&resultVector, output, str);
}

// STRING TO DECIMAL SPECIALIZATION
template<>
inline void CastToDecimal::operation(ku_string_t& input, int16_t& output, const ValueVector&,
    const ValueVector& outputVec) {
    decimalCast((const char*)input.getData(), input.len, output, outputVec.dataType);
}

template<>
inline void CastToDecimal::operation(ku_string_t& input, int32_t& output, const ValueVector&,
    const ValueVector& outputVec) {
    decimalCast((const char*)input.getData(), input.len, output, outputVec.dataType);
}

template<>
inline void CastToDecimal::operation(ku_string_t& input, int64_t& output, const ValueVector&,
    const ValueVector& outputVec) {
    decimalCast((const char*)input.getData(), input.len, output, outputVec.dataType);
}

template<>
inline void CastToDecimal::operation(ku_string_t& input, common::int128_t& output,
    const ValueVector&, const ValueVector& outputVec) {
    decimalCast((const char*)input.getData(), input.len, output, outputVec.dataType);
}

} // namespace function
} // namespace lbug
