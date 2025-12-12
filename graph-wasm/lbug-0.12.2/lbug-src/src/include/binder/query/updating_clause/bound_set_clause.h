#pragma once

#include "bound_set_info.h"
#include "bound_updating_clause.h"

namespace lbug {
namespace binder {

class BoundSetClause final : public BoundUpdatingClause {
public:
    BoundSetClause() : BoundUpdatingClause{common::ClauseType::SET} {}

    void addInfo(BoundSetPropertyInfo info) { infos.push_back(std::move(info)); }
    const std::vector<BoundSetPropertyInfo>& getInfos() const { return infos; }

    bool hasNodeInfo() const {
        return hasInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    std::vector<BoundSetPropertyInfo> getNodeInfos() const {
        return getInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::NODE;
        });
    }
    bool hasRelInfo() const {
        return hasInfo([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }
    std::vector<BoundSetPropertyInfo> getRelInfos() const {
        return getInfos([](const BoundSetPropertyInfo& info) {
            return info.tableType == common::TableType::REL;
        });
    }

private:
    bool hasInfo(const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;
    std::vector<BoundSetPropertyInfo> getInfos(
        const std::function<bool(const BoundSetPropertyInfo& info)>& check) const;

private:
    std::vector<BoundSetPropertyInfo> infos;
};

} // namespace binder
} // namespace lbug
