#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API TransactionManagerException : public Exception {
public:
    explicit TransactionManagerException(const std::string& msg) : Exception(msg){};
};

} // namespace common
} // namespace lbug
