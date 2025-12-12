#include "binder/query/updating_clause/bound_set_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

bool BoundSetClause::hasInfo(const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    for (auto& info : infos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<BoundSetPropertyInfo> BoundSetClause::getInfos(
    const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    std::vector<BoundSetPropertyInfo> result;
    for (auto& info : infos) {
        if (check(info)) {
            result.push_back(info.copy());
        }
    }
    return result;
}

} // namespace binder
} // namespace lbug
