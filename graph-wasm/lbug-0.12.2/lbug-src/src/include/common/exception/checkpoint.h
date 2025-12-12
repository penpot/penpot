#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API CheckpointException : public Exception {
public:
    explicit CheckpointException(const std::exception& e) : Exception(e.what()){};
};

} // namespace common
} // namespace lbug
