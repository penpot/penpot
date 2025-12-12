#pragma once

#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API IOException : public Exception {
public:
    explicit IOException(const std::string& msg) : Exception("IO exception: " + msg) {}
};

} // namespace common
} // namespace lbug
