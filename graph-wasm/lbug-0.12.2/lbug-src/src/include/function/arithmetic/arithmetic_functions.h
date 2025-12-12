#pragma once

#include <cmath>

#include "common/types/int128_t.h"
#include "common/types/uint128_t.h"

namespace lbug {
namespace function {

struct Power {
    template<class A, class B, class R>
    static inline void operation(A& left, B& right, R& result) {
        result = pow(left, right);
    }
};

struct Floor {
    template<class T>
    static inline void operation(T& input, T& result) {
        result = floor(input);
    }
};

template<>
inline void Floor::operation(common::int128_t& input, common::int128_t& result) {
    result = input;
}

template<>
inline void Floor::operation(common::uint128_t& input, common::uint128_t& result) {
    result = input;
}

struct Ceil {
    template<class T>
    static inline void operation(T& input, T& result) {
        result = ceil(input);
    }
};

template<>
inline void Ceil::operation(common::int128_t& input, common::int128_t& result) {
    result = input;
}

template<>
inline void Ceil::operation(common::uint128_t& input, common::uint128_t& result) {
    result = input;
}

struct Sin {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = sin(input);
    }
};

struct Cos {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = cos(input);
    }
};

struct Tan {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = tan(input);
    }
};

struct Cot {
    template<class T>
    static inline void operation(T& input, double& result) {
        double tanValue = 0;
        Tan::operation(input, tanValue);
        result = 1 / tanValue;
    }
};

struct Asin {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = asin(input);
    }
};

struct Acos {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = acos(input);
    }
};

struct Atan {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = atan(input);
    }
};

struct Even {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = input >= 0 ? ceil(input) : floor(input);
        // Note: c++ doesn't support double % integer, so we have to use the following code to check
        // whether result is odd or even.
        if (std::floor(result / 2) * 2 != result) {
            result += (input >= 0 ? 1 : -1);
        }
    }
};

struct Factorial {
    static inline void operation(int64_t& input, int64_t& result) {
        result = 1;
        for (int64_t i = 2; i <= input; i++) {
            result *= i;
        }
    }
};

struct Sign {
    template<class T>
    static inline void operation(T& input, int64_t& result) {
        result = (input > 0) - (input < 0);
    }
};

struct Sqrt {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = sqrt(input);
    }
};

struct Cbrt {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = cbrt(input);
    }
};

struct Gamma {
    template<class T>
    static inline void operation(T& input, T& result) {
        result = tgamma(input);
    }
};

struct Lgamma {
    template<class T>
    static inline void operation(T& input, double& result) {
        result =
            lgamma(input); // NOLINT(concurrency-mt-unsafe): We don't use the thread-unsafe signgam.
    }
};

struct Ln {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = log(input);
    }
};

struct Log {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = log10(input);
    }
};

struct Log2 {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = log2(input);
    }
};

struct Degrees {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = input * 180 / M_PI;
    }
};

struct Radians {
    template<class T>
    static inline void operation(T& input, double& result) {
        result = input * M_PI / 180;
    }
};

struct Atan2 {
    template<class A, class B>
    static inline void operation(A& left, B& right, double& result) {
        result = atan2(left, right);
    }
};

struct Round {
    template<class A, class B>
    static inline void operation(A& left, B& right, double& result) {
        auto multiplier = pow(10, right);
        result = round(left * multiplier) / multiplier;
    }
};

struct BitwiseXor {
    static inline void operation(int64_t& left, int64_t& right, int64_t& result) {
        result = left ^ right;
    }
};

struct BitwiseAnd {
    static inline void operation(int64_t& left, int64_t& right, int64_t& result) {
        result = left & right;
    }
};

struct BitwiseOr {
    static inline void operation(int64_t& left, int64_t& right, int64_t& result) {
        result = left | right;
    }
};

struct BitShiftLeft {
    static inline void operation(int64_t& left, int64_t& right, int64_t& result) {
        result = left << right;
    }
};

struct BitShiftRight {
    static inline void operation(int64_t& left, int64_t& right, int64_t& result) {
        result = left >> right;
    }
};

struct Pi {
    static inline void operation(double& result) { result = M_PI; }
};

} // namespace function
} // namespace lbug
