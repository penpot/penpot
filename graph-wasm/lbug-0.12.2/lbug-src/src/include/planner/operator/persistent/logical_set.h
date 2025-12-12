#pragma once

#include "binder/query/updating_clause/bound_set_info.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalSetProperty final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::SET_PROPERTY;

public:
    LogicalSetProperty(std::vector<binder::BoundSetPropertyInfo> infos,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, infos{std::move(infos)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    f_group_pos_set getGroupsPosToFlatten(uint32_t idx) const;

    std::string getExpressionsForPrinting() const override;

    common::TableType getTableType() const;
    const std::vector<binder::BoundSetPropertyInfo>& getInfos() const { return infos; }
    const binder::BoundSetPropertyInfo& getInfo(uint32_t idx) const { return infos[idx]; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalSetProperty>(copyVector(infos), children[0]->copy());
    }

private:
    std::vector<binder::BoundSetPropertyInfo> infos;
};

} // namespace planner
} // namespace lbug
