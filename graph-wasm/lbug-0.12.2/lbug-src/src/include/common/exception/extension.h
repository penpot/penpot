#pragma once

#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API ExtensionException : public Exception {
public:
    explicit ExtensionException(const std::string& msg)
        : Exception("Extension exception: " + msg) {}
};

} // namespace common
} // namespace lbug
