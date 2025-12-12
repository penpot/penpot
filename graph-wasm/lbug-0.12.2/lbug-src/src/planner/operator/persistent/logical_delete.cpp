#include "planner/operator/persistent/logical_delete.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"
#include "planner/operator/factorization/flatten_resolver.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

std::string LogicalDelete::getExpressionsForPrinting() const {
    expression_vector patterns;
    for (auto& info : infos) {
        patterns.push_back(info.pattern);
    }
    return ExpressionUtil::toString(patterns);
}

f_group_pos_set LogicalDelete::getGroupsPosToFlatten() const {
    KU_ASSERT(!infos.empty());
    const auto childSchema = children[0]->getSchema();
    f_group_pos_set dependentGroupPos;
    switch (infos[0].tableType) {
    case TableType::NODE: {
        for (auto& info : infos) {
            auto nodeID = info.pattern->constCast<NodeExpression>().getInternalID();
            dependentGroupPos.insert(childSchema->getGroupPos(*nodeID));
        }
    } break;
    case TableType::REL: {
        for (auto& info : infos) {
            auto& rel = info.pattern->constCast<RelExpression>();
            dependentGroupPos.insert(childSchema->getGroupPos(*rel.getSrcNode()->getInternalID()));
            dependentGroupPos.insert(childSchema->getGroupPos(*rel.getDstNode()->getInternalID()));
        }
    } break;
    default:
        KU_UNREACHABLE;
    }
    return FlattenAll::getGroupsPosToFlatten(dependentGroupPos, *childSchema);
}

} // namespace planner
} // namespace lbug
