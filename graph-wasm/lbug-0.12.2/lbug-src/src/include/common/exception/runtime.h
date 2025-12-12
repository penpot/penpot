#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API RuntimeException : public Exception {
public:
    explicit RuntimeException(const std::string& msg) : Exception("Runtime exception: " + msg){};
};

} // namespace common
} // namespace lbug
