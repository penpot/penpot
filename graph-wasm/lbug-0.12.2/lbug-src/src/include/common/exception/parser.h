#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API ParserException : public Exception {
public:
    static constexpr const char* ERROR_PREFIX = "Parser exception: ";

    explicit ParserException(const std::string& msg) : Exception(ERROR_PREFIX + msg){};
};

} // namespace common
} // namespace lbug
