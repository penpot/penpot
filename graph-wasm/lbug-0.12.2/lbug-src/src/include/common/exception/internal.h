#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API InternalException : public Exception {
public:
    explicit InternalException(const std::string& msg) : Exception(msg){};
};

} // namespace common
} // namespace lbug
