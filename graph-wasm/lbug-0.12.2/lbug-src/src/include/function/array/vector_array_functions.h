#pragma once

#include "function/function.h"
#include "function/list/vector_list_functions.h"

namespace lbug {
namespace function {

struct ArrayValueFunction {
    static constexpr const char* name = "ARRAY_VALUE";

    static function_set getFunctionSet();
};

struct ArrayCrossProductFunction {
    static constexpr const char* name = "ARRAY_CROSS_PRODUCT";

    static function_set getFunctionSet();
};

struct ArrayCosineSimilarityFunction {
    static constexpr const char* name = "ARRAY_COSINE_SIMILARITY";

    static function_set getFunctionSet();
};

struct ArrayDistanceFunction {
    static constexpr const char* name = "ARRAY_DISTANCE";

    static function_set getFunctionSet();
};

struct ArraySquaredDistanceFunction {
    static constexpr const char* name = "ARRAY_SQUARED_DISTANCE";

    static function_set getFunctionSet();
};

struct ArrayInnerProductFunction {
    static constexpr const char* name = "ARRAY_INNER_PRODUCT";

    static function_set getFunctionSet();
};

struct ArrayDotProductFunction {
    static constexpr const char* name = "ARRAY_DOT_PRODUCT";

    static function_set getFunctionSet();
};

struct ArrayConcatFunction : public ListConcatFunction {
    static constexpr const char* name = "ARRAY_CONCAT";
};

struct ArrayCatFunction {
    using alias = ArrayConcatFunction;

    static constexpr const char* name = "ARRAY_CAT";
};

struct ArrayAppendFunction : public ListAppendFunction {
    static constexpr const char* name = "ARRAY_APPEND";
};

struct ArrayPushBackFunction {
    using alias = ArrayAppendFunction;

    static constexpr const char* name = "ARRAY_PUSH_BACK";
};

struct ArrayPrependFunction : public ListPrependFunction {
    static constexpr const char* name = "ARRAY_PREPEND";
};

struct ArrayPushFrontFunction {
    using alias = ArrayPrependFunction;

    static constexpr const char* name = "ARRAY_PUSH_FRONT";
};

struct ArrayPositionFunction : public ListPositionFunction {
    static constexpr const char* name = "ARRAY_POSITION";
};

struct ArrayIndexOfFunction {
    using alias = ArrayPositionFunction;

    static constexpr const char* name = "ARRAY_INDEXOF";
};

struct ArrayContainsFunction : public ListContainsFunction {
    static constexpr const char* name = "ARRAY_CONTAINS";
};

struct ArrayHasFunction {
    using alias = ArrayContainsFunction;

    static constexpr const char* name = "ARRAY_HAS";
};

struct ArraySliceFunction : public ListSliceFunction {
    static constexpr const char* name = "ARRAY_SLICE";
};

} // namespace function
} // namespace lbug
