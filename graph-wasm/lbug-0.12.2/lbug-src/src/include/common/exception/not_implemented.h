#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API NotImplementedException : public Exception {
public:
    explicit NotImplementedException(const std::string& msg) : Exception(msg){};
};

} // namespace common
} // namespace lbug
