#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API OverflowException : public Exception {
public:
    explicit OverflowException(const std::string& msg) : Exception("Overflow exception: " + msg) {}
};

} // namespace common
} // namespace lbug
