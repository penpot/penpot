#pragma once

#include "common/api.h"
#include "exception.h"

namespace lbug {
namespace common {

class LBUG_API CatalogException : public Exception {
public:
    explicit CatalogException(const std::string& msg) : Exception("Catalog exception: " + msg){};
};

} // namespace common
} // namespace lbug
