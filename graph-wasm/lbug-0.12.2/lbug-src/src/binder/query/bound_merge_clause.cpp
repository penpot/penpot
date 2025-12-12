#include "binder/query/updating_clause/bound_merge_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

bool BoundMergeClause::hasInsertInfo(
    const std::function<bool(const BoundInsertInfo&)>& check) const {
    for (auto& info : insertInfos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<const BoundInsertInfo*> BoundMergeClause::getInsertInfos(
    const std::function<bool(const BoundInsertInfo&)>& check) const {
    std::vector<const BoundInsertInfo*> result;
    for (auto& info : insertInfos) {
        if (check(info)) {
            result.push_back(&info);
        }
    }
    return result;
}

bool BoundMergeClause::hasOnMatchSetInfo(
    const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    for (auto& info : onMatchSetPropertyInfos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<BoundSetPropertyInfo> BoundMergeClause::getOnMatchSetInfos(
    const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    std::vector<BoundSetPropertyInfo> result;
    for (auto& info : onMatchSetPropertyInfos) {
        if (check(info)) {
            result.push_back(info.copy());
        }
    }
    return result;
}

bool BoundMergeClause::hasOnCreateSetInfo(
    const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    for (auto& info : onCreateSetPropertyInfos) {
        if (check(info)) {
            return true;
        }
    }
    return false;
}

std::vector<BoundSetPropertyInfo> BoundMergeClause::getOnCreateSetInfos(
    const std::function<bool(const BoundSetPropertyInfo&)>& check) const {
    std::vector<BoundSetPropertyInfo> result;
    for (auto& info : onCreateSetPropertyInfos) {
        if (check(info)) {
            result.push_back(info.copy());
        }
    }
    return result;
}

} // namespace binder
} // namespace lbug
