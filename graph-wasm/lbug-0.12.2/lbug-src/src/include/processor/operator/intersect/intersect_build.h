#pragma once

#include "binder/expression/expression_util.h"
#include "processor/operator/hash_join/hash_join_build.h"

namespace lbug {
namespace processor {

struct IntersectBuildPrintInfo final : OPPrintInfo {
    binder::expression_vector keys;
    binder::expression_vector payloads;

    IntersectBuildPrintInfo(binder::expression_vector keys, binder::expression_vector payloads)
        : keys{std::move(keys)}, payloads(std::move(payloads)) {}

    std::string toString() const override {
        std::string result = "Keys: ";
        result += binder::ExpressionUtil::toString(keys);
        if (!payloads.empty()) {
            result += ", Payloads: ";
            result += binder::ExpressionUtil::toString(payloads);
        }
        return result;
    }

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<IntersectBuildPrintInfo>(new IntersectBuildPrintInfo(*this));
    }

private:
    IntersectBuildPrintInfo(const IntersectBuildPrintInfo& other)
        : OPPrintInfo{other}, keys{other.keys}, payloads{other.payloads} {}
};

class IntersectBuild final : public HashJoinBuild {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::INTERSECT_BUILD;

public:
    IntersectBuild(std::shared_ptr<HashJoinSharedState> sharedState, HashJoinBuildInfo info,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : HashJoinBuild{type_, std::move(sharedState), std::move(info), std::move(child), id,
              std::move(printInfo)} {}

    uint64_t appendVectors() final {
        KU_ASSERT(keyVectors.size() == 1);
        return hashTable->appendVectorWithSorting(keyVectors[0], payloadVectors);
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<IntersectBuild>(sharedState, info.copy(), children[0]->copy(), id,
            printInfo->copy());
    }
};

} // namespace processor
} // namespace lbug
