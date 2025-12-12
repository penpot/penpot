#include "common/enums/path_semantic.h"

#include "common/assert.h"
#include "common/exception/binder.h"
#include "common/string_format.h"
#include "common/string_utils.h"

namespace lbug {
namespace common {

PathSemantic PathSemanticUtils::fromString(const std::string& str) {
    auto normalizedStr = StringUtils::getUpper(str);
    if (normalizedStr == "WALK") {
        return PathSemantic::WALK;
    }
    if (normalizedStr == "TRAIL") {
        return PathSemantic::TRAIL;
    }
    if (normalizedStr == "ACYCLIC") {
        return PathSemantic::ACYCLIC;
    }
    throw BinderException(stringFormat(
        "Cannot parse {} as a path semantic. Supported inputs are [WALK, TRAIL, ACYCLIC]", str));
}

std::string PathSemanticUtils::toString(PathSemantic semantic) {
    switch (semantic) {
    case PathSemantic::WALK:
        return "WALK";
    case PathSemantic::TRAIL:
        return "TRAIL";
    case PathSemantic::ACYCLIC:
        return "ACYCLIC";
    default:
        KU_UNREACHABLE;
    }
}

} // namespace common
} // namespace lbug
