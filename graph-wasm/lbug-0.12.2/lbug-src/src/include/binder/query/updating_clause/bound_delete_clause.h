#pragma once

#include "bound_delete_info.h"
#include "bound_updating_clause.h"

namespace lbug {
namespace binder {

class BoundDeleteClause final : public BoundUpdatingClause {
public:
    BoundDeleteClause() : BoundUpdatingClause{common::ClauseType::DELETE_} {};

    void addInfo(BoundDeleteInfo info) { infos.push_back(std::move(info)); }

    bool hasNodeInfo() const {
        return hasInfo(
            [](const BoundDeleteInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    std::vector<BoundDeleteInfo> getNodeInfos() const {
        return getInfos(
            [](const BoundDeleteInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    bool hasRelInfo() const {
        return hasInfo(
            [](const BoundDeleteInfo& info) { return info.tableType == common::TableType::REL; });
    }
    std::vector<BoundDeleteInfo> getRelInfos() const {
        return getInfos(
            [](const BoundDeleteInfo& info) { return info.tableType == common::TableType::REL; });
    }

private:
    bool hasInfo(const std::function<bool(const BoundDeleteInfo& info)>& check) const;
    std::vector<BoundDeleteInfo> getInfos(
        const std::function<bool(const BoundDeleteInfo& info)>& check) const;

private:
    std::vector<BoundDeleteInfo> infos;
};

} // namespace binder
} // namespace lbug
