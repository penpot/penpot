#include "planner/operator/logical_filter.h"

#include "planner/operator/factorization/flatten_resolver.h"

namespace lbug {
namespace planner {

f_group_pos_set LogicalFilter::getGroupsPosToFlatten() {
    auto childSchema = children[0]->getSchema();
    return FlattenAllButOne::getGroupsPosToFlatten(expression, *childSchema);
}

f_group_pos LogicalFilter::getGroupPosToSelect() const {
    auto childSchema = children[0]->getSchema();
    auto analyzer = GroupDependencyAnalyzer(false, *childSchema);
    analyzer.visit(expression);
    SchemaUtils::validateAtMostOneUnFlatGroup(analyzer.getDependentGroups(), *childSchema);
    return SchemaUtils::getLeadingGroupPos(analyzer.getDependentGroups(), *childSchema);
}

} // namespace planner
} // namespace lbug
