#include "planner/operator/extend/base_logical_extend.h"

using namespace lbug::common;

namespace lbug {
namespace planner {

static std::string relToString(const binder::RelExpression& rel) {
    auto result = rel.toString();
    switch (rel.getRelType()) {
    case QueryRelType::SHORTEST: {
        result += "SHORTEST";
    } break;
    case QueryRelType::ALL_SHORTEST: {
        result += "ALL SHORTEST";
    } break;
    default:
        break;
    }
    if (QueryRelTypeUtils::isRecursive(rel.getRelType())) {
        auto bindData = rel.getRecursiveInfo()->bindData.get();
        result += std::to_string(bindData->lowerBound);
        result += "..";
        result += std::to_string(bindData->upperBound);
    }
    return result;
}

std::string BaseLogicalExtend::getExpressionsForPrinting() const {
    auto result = boundNode->toString();
    switch (direction) {
    case ExtendDirection::FWD: {
        result += "-";
        result += relToString(*rel);
        result += "->";
    } break;
    case ExtendDirection::BWD: {
        result += "<-";
        result += relToString(*rel);
        result += "-";
    } break;
    case ExtendDirection::BOTH: {
        result += "<-";
        result += relToString(*rel);
        result += "->";
    } break;
    default:
        KU_UNREACHABLE;
    }
    result += nbrNode->toString();
    return result;
}

} // namespace planner
} // namespace lbug
