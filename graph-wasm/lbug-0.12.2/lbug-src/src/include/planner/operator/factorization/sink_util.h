#pragma once

#include "planner/operator/schema.h"

namespace lbug {
namespace planner {

// This class contains the logic for re-computing factorization structure after sinking
class SinkOperatorUtil {
public:
    static void mergeSchema(const Schema& inputSchema,
        const binder::expression_vector& expressionsToMerge, Schema& resultSchema);

    static void recomputeSchema(const Schema& inputSchema,
        const binder::expression_vector& expressionsToMerge, Schema& resultSchema);

private:
    static std::unordered_map<f_group_pos, binder::expression_vector> getUnFlatPayloadsPerGroup(
        const Schema& schema, const binder::expression_vector& payloads);

    static binder::expression_vector getFlatPayloads(const Schema& schema,
        const binder::expression_vector& payloads);

    static uint32_t appendPayloadsToNewGroup(Schema& schema, binder::expression_vector& payloads);
};

} // namespace planner
} // namespace lbug
