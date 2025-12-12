#pragma once

#include "processor/operator/sink.h"

namespace lbug {
namespace processor {
class PhysicalPlan;

struct ProfileInfo {
    PhysicalPlan* physicalPlan = nullptr;
};

class Profile final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::PROFILE;

public:
    Profile(ProfileInfo info, std::shared_ptr<FactorizedTable> messageTable, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)}, info{info} {}

    void setPhysicalPlan(PhysicalPlan* physicalPlan) { info.physicalPlan = physicalPlan; }

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<Profile>(info, messageTable, id, printInfo->copy());
    }

private:
    ProfileInfo info;
};

} // namespace processor
} // namespace lbug
