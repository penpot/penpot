#pragma once

#include "binder/query/updating_clause/bound_delete_info.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalDeletePrintInfo final : OPPrintInfo {
    std::vector<binder::BoundDeleteInfo> infos;

    explicit LogicalDeletePrintInfo(std::vector<binder::BoundDeleteInfo> infos)
        : infos{std::move(infos)} {}

    std::string toString() const override {
        std::string result = "";
        for (auto& info : infos) {
            result += info.toString();
        }
        return result;
    }
};

class LogicalDelete final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::DELETE;

public:
    LogicalDelete(std::vector<binder::BoundDeleteInfo> infos,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, infos{std::move(infos)} {}

    common::TableType getTableType() const {
        KU_ASSERT(!infos.empty());
        return infos[0].tableType;
    }
    const std::vector<binder::BoundDeleteInfo>& getInfos() const { return infos; }

    void computeFactorizedSchema() override { copyChildSchema(0); }
    void computeFlatSchema() override { copyChildSchema(0); }

    std::string getExpressionsForPrinting() const override;

    f_group_pos_set getGroupsPosToFlatten() const;

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalDeletePrintInfo>(copyVector(infos));
    }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalDelete>(copyVector(infos), children[0]->copy());
    }

private:
    std::vector<binder::BoundDeleteInfo> infos;
};

} // namespace planner
} // namespace lbug
