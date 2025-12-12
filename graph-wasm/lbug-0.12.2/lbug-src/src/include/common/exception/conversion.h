#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API ConversionException : public Exception {
public:
    explicit ConversionException(const std::string& msg)
        : Exception("Conversion exception: " + msg) {}
};

} // namespace common
} // namespace lbug
