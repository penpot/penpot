#include "planner/operator/scan/logical_scan_node_table.h"

namespace lbug {
namespace planner {

LogicalScanNodeTable::LogicalScanNodeTable(const LogicalScanNodeTable& other)
    : LogicalOperator{type_}, scanType{other.scanType}, nodeID{other.nodeID},
      nodeTableIDs{other.nodeTableIDs}, properties{other.properties},
      propertyPredicates{copyVector(other.propertyPredicates)} {
    if (other.extraInfo != nullptr) {
        setExtraInfo(other.extraInfo->copy());
    }
    this->cardinality = other.cardinality;
}

void LogicalScanNodeTable::computeFactorizedSchema() {
    createEmptySchema();
    const auto groupPos = schema->createGroup();
    KU_ASSERT(groupPos == 0);
    schema->insertToGroupAndScope(nodeID, groupPos);
    for (auto& property : properties) {
        schema->insertToGroupAndScope(property, groupPos);
    }
    switch (scanType) {
    case LogicalScanNodeTableType::PRIMARY_KEY_SCAN: {
        schema->setGroupAsSingleState(groupPos);
    } break;
    default:
        break;
    }
}

void LogicalScanNodeTable::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
    schema->insertToGroupAndScope(nodeID, 0);
    for (auto& property : properties) {
        schema->insertToGroupAndScope(property, 0);
    }
}

std::unique_ptr<LogicalOperator> LogicalScanNodeTable::copy() {
    return std::make_unique<LogicalScanNodeTable>(*this);
}

} // namespace planner
} // namespace lbug
