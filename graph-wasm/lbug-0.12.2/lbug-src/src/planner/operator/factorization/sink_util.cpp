#include "planner/operator/factorization/sink_util.h"

namespace lbug {
namespace planner {

void SinkOperatorUtil::mergeSchema(const Schema& inputSchema,
    const binder::expression_vector& expressionsToMerge, Schema& resultSchema) {
    auto flatPayloads = getFlatPayloads(inputSchema, expressionsToMerge);
    auto unFlatPayloadsPerGroup = getUnFlatPayloadsPerGroup(inputSchema, expressionsToMerge);
    if (unFlatPayloadsPerGroup.empty()) {
        appendPayloadsToNewGroup(resultSchema, flatPayloads);
    } else {
        if (!flatPayloads.empty()) {
            auto groupPos = appendPayloadsToNewGroup(resultSchema, flatPayloads);
            resultSchema.setGroupAsSingleState(groupPos);
        }
        for (auto& [inputGroupPos, payloads] : unFlatPayloadsPerGroup) {
            auto resultGroupPos = appendPayloadsToNewGroup(resultSchema, payloads);
            resultSchema.getGroup(resultGroupPos)
                ->setMultiplier(inputSchema.getGroup(inputGroupPos)->getMultiplier());
        }
    }
}

void SinkOperatorUtil::recomputeSchema(const Schema& inputSchema,
    const binder::expression_vector& expressionsToMerge, Schema& resultSchema) {
    KU_ASSERT(!expressionsToMerge.empty());
    resultSchema.clear();
    mergeSchema(inputSchema, expressionsToMerge, resultSchema);
}

std::unordered_map<f_group_pos, binder::expression_vector>
SinkOperatorUtil::getUnFlatPayloadsPerGroup(const Schema& schema,
    const binder::expression_vector& payloads) {
    std::unordered_map<f_group_pos, binder::expression_vector> result;
    for (auto& payload : payloads) {
        auto groupPos = schema.getGroupPos(*payload);
        if (schema.getGroup(groupPos)->isFlat()) {
            continue;
        }
        if (!result.contains(groupPos)) {
            result.insert({groupPos, binder::expression_vector{}});
        }
        result.at(groupPos).push_back(payload);
    }
    return result;
}

binder::expression_vector SinkOperatorUtil::getFlatPayloads(const Schema& schema,
    const binder::expression_vector& payloads) {
    binder::expression_vector result;
    for (auto& payload : payloads) {
        if (schema.getGroup(payload)->isFlat()) {
            result.push_back(payload);
        }
    }
    return result;
}

uint32_t SinkOperatorUtil::appendPayloadsToNewGroup(Schema& schema,
    binder::expression_vector& payloads) {
    auto outputGroupPos = schema.createGroup();
    for (auto& payload : payloads) {
        schema.insertToGroupAndScope(payload, outputGroupPos);
    }
    return outputGroupPos;
}

} // namespace planner
} // namespace lbug
