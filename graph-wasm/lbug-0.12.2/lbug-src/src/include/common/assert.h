#pragma once

#include "common/exception/internal.h"
#include "common/string_format.h"

namespace lbug {
namespace common {

[[noreturn]] inline void kuAssertFailureInternal(const char* condition_name, const char* file,
    int linenr) {
    // LCOV_EXCL_START
    throw InternalException(stringFormat("Assertion failed in file \"{}\" on line {}: {}", file,
        linenr, condition_name));
    // LCOV_EXCL_STOP
}

#define KU_ASSERT_UNCONDITIONAL(condition)                                                         \
    static_cast<bool>(condition) ?                                                                 \
        void(0) :                                                                                  \
        lbug::common::kuAssertFailureInternal(#condition, __FILE__, __LINE__)

#if defined(LBUG_RUNTIME_CHECKS) || !defined(NDEBUG)
#define RUNTIME_CHECK(code) code
#define KU_ASSERT(condition) KU_ASSERT_UNCONDITIONAL(condition)
#else
#define KU_ASSERT(condition) void(0)
#define RUNTIME_CHECK(code) void(0)
#endif

#define KU_UNREACHABLE                                                                             \
    /* LCOV_EXCL_START */ [[unlikely]] lbug::common::kuAssertFailureInternal("KU_UNREACHABLE",     \
        __FILE__, __LINE__) /* LCOV_EXCL_STOP */
#define KU_UNUSED(expr) (void)(expr)

} // namespace common
} // namespace lbug
