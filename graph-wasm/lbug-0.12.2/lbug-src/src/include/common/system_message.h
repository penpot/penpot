#pragma once

#include <cerrno>
#include <string>
#include <system_error>

#include "common/api.h"

namespace lbug {
namespace common {

inline std::string systemErrMessage(int code) {
    // System errors are unexpected. For anything expected, we should catch it explicitly and
    // provide a better error message to the user.
    // LCOV_EXCL_START
    return std::system_category().message(code);
    // LCOV_EXCL_STOP
}

inline std::string posixErrMessage() {
    // LCOV_EXCL_START
    return systemErrMessage(errno);
    // LCOV_EXCL_STOP
}

LBUG_API std::string dlErrMessage();

} // namespace common
} // namespace lbug
