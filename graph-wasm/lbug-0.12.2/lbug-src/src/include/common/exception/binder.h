#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API BinderException : public Exception {
public:
    explicit BinderException(const std::string& msg) : Exception("Binder exception: " + msg){};
};

} // namespace common
} // namespace lbug
