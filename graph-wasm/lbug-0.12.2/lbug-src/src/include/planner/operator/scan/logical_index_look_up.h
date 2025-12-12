#pragma once

#include "binder/copy/index_look_up_info.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

// This operator is specifically used to transform primary key to offset during relationship copy.
// So it is not a source operator. I would suggest move this logic into rel copy instead of
// maintaining an operator.
class LogicalPrimaryKeyLookup final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::INDEX_LOOK_UP;

public:
    LogicalPrimaryKeyLookup(std::vector<binder::IndexLookupInfo> infos,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, infos{std::move(infos)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override;

    uint32_t getNumInfos() const { return infos.size(); }
    const binder::IndexLookupInfo& getInfo(uint32_t idx) const { return infos[idx]; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalPrimaryKeyLookup>(infos, children[0]->copy());
    }

private:
    std::vector<binder::IndexLookupInfo> infos;
};

} // namespace planner
} // namespace lbug
