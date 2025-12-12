#include "planner/operator/persistent/logical_copy_to.h"

#include "planner/operator/factorization/flatten_resolver.h"

namespace lbug {
namespace planner {

std::string LogicalCopyToPrintInfo::toString() const {
    std::string result = "";
    result += "Export: ";
    for (auto& name : columnNames) {
        result += name + ", ";
    }
    result += "To: " + fileName;
    return result;
}

void LogicalCopyTo::computeFactorizedSchema() {
    copyChildSchema(0);
}

void LogicalCopyTo::computeFlatSchema() {
    copyChildSchema(0);
}

f_group_pos_set LogicalCopyTo::getGroupsPosToFlatten() {
    auto childSchema = children[0]->getSchema();
    auto dependentGroupsPos = childSchema->getGroupsPosInScope();
    return FlattenAllButOne::getGroupsPosToFlatten(dependentGroupsPos, *childSchema);
}

} // namespace planner
} // namespace lbug
