#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API InterruptException : public Exception {
public:
    explicit InterruptException() : Exception("Interrupted."){};
};

} // namespace common
} // namespace lbug
