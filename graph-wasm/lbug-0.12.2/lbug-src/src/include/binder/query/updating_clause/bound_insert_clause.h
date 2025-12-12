#pragma once

#include "bound_insert_info.h"
#include "bound_updating_clause.h"

namespace lbug {
namespace binder {

class BoundInsertClause final : public BoundUpdatingClause {
public:
    explicit BoundInsertClause(std::vector<BoundInsertInfo> infos)
        : BoundUpdatingClause{common::ClauseType::INSERT}, infos{std::move(infos)} {}

    const std::vector<BoundInsertInfo>& getInfos() const { return infos; }

    bool hasNodeInfo() const {
        return hasInfo(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    std::vector<const BoundInsertInfo*> getNodeInfos() const {
        return getInfos(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::NODE; });
    }
    bool hasRelInfo() const {
        return hasInfo(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::REL; });
    }
    std::vector<const BoundInsertInfo*> getRelInfos() const {
        return getInfos(
            [](const BoundInsertInfo& info) { return info.tableType == common::TableType::REL; });
    }

private:
    bool hasInfo(const std::function<bool(const BoundInsertInfo& info)>& check) const;
    std::vector<const BoundInsertInfo*> getInfos(
        const std::function<bool(const BoundInsertInfo& info)>& check) const;

private:
    std::vector<BoundInsertInfo> infos;
};

} // namespace binder
} // namespace lbug
