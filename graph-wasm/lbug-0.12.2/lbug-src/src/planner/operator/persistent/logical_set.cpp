#include "planner/operator/persistent/logical_set.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/rel_expression.h"
#include "planner/operator/factorization/flatten_resolver.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

void LogicalSetProperty::computeFactorizedSchema() {
    copyChildSchema(0);
}

void LogicalSetProperty::computeFlatSchema() {
    copyChildSchema(0);
}

f_group_pos_set LogicalSetProperty::getGroupsPosToFlatten(uint32_t idx) const {
    f_group_pos_set result;
    auto childSchema = children[0]->getSchema();
    auto& info = infos[idx];
    switch (getTableType()) {
    case TableType::NODE: {
        auto node = info.pattern->constPtrCast<NodeExpression>();
        result.insert(childSchema->getGroupPos(*node->getInternalID()));
    } break;
    case TableType::REL: {
        auto rel = info.pattern->constPtrCast<RelExpression>();
        result.insert(childSchema->getGroupPos(*rel->getSrcNode()->getInternalID()));
        result.insert(childSchema->getGroupPos(*rel->getDstNode()->getInternalID()));
    } break;
    default:
        KU_UNREACHABLE;
    }
    auto analyzer = GroupDependencyAnalyzer(false, *childSchema);
    analyzer.visit(info.columnData);
    for (auto& groupPos : analyzer.getDependentGroups()) {
        result.insert(groupPos);
    }
    return FlattenAll::getGroupsPosToFlatten(result, *childSchema);
}

std::string LogicalSetProperty::getExpressionsForPrinting() const {
    std::string result =
        ExpressionUtil::toString(std::make_pair(infos[0].column, infos[0].columnData));
    for (auto i = 1u; i < infos.size(); ++i) {
        result += ExpressionUtil::toString(std::make_pair(infos[i].column, infos[i].columnData));
    }
    return result;
}

common::TableType LogicalSetProperty::getTableType() const {
    KU_ASSERT(!infos.empty());
    return infos[0].tableType;
}

} // namespace planner
} // namespace lbug
