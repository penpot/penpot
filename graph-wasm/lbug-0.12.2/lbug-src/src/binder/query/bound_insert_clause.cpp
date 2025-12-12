#include "binder/query/updating_clause/bound_insert_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

bool BoundInsertClause::hasInfo(const std::function<bool(const BoundInsertInfo&)>& check) const {
    for (auto& info : infos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<const BoundInsertInfo*> BoundInsertClause::getInfos(
    const std::function<bool(const BoundInsertInfo&)>& check) const {
    std::vector<const BoundInsertInfo*> result;
    for (auto& info : infos) {
        if (check(info)) {
            result.push_back(&info);
        }
    }
    return result;
}

} // namespace binder
} // namespace lbug
