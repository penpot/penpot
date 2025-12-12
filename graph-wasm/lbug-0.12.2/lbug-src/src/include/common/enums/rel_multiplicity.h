#pragma once

#include <cstdint>
#include <string>

namespace lbug {
namespace common {

enum class RelMultiplicity : uint8_t { MANY, ONE };

struct RelMultiplicityUtils {
    static RelMultiplicity getFwd(const std::string& str);
    static RelMultiplicity getBwd(const std::string& str);
    static std::string toString(RelMultiplicity multiplicity);
};

} // namespace common
} // namespace lbug
