#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API StorageException : public Exception {
public:
    explicit StorageException(const std::string& msg) : Exception("Storage exception: " + msg){};
};

} // namespace common
} // namespace lbug
