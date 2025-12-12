#include "optimizer/logical_operator_collector.h"

#include "planner/operator/scan/logical_scan_node_table.h"

namespace lbug {
namespace optimizer {

void LogicalOperatorCollector::collect(planner::LogicalOperator* op) {
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        collect(op->getChild(i).get());
    }
    visitOperatorSwitch(op);
}

void LogicalIndexScanNodeCollector::visitScanNodeTable(planner::LogicalOperator* op) {
    auto scan = op->constCast<planner::LogicalScanNodeTable>();
    if (scan.getScanType() == planner::LogicalScanNodeTableType::PRIMARY_KEY_SCAN) {
        ops.push_back(op);
    }
}

} // namespace optimizer
} // namespace lbug
