#include "binder/query/updating_clause/bound_delete_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

bool BoundDeleteClause::hasInfo(const std::function<bool(const BoundDeleteInfo&)>& check) const {
    for (auto& info : infos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<BoundDeleteInfo> BoundDeleteClause::getInfos(
    const std::function<bool(const BoundDeleteInfo&)>& check) const {
    std::vector<BoundDeleteInfo> result;
    for (auto& info : infos) {
        if (check(info)) {
            result.push_back(info.copy());
        }
    }
    return result;
}

} // namespace binder
} // namespace lbug
