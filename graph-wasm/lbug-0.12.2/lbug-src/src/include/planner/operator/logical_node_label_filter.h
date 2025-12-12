#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalNodeLabelFilter final : public LogicalOperator {
public:
    LogicalNodeLabelFilter(std::shared_ptr<binder::Expression> nodeID,
        std::unordered_set<common::table_id_t> tableIDSet, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{LogicalOperatorType::NODE_LABEL_FILTER, std::move(child)},
          nodeID{std::move(nodeID)}, tableIDSet{std::move(tableIDSet)} {}

    inline void computeFactorizedSchema() override { copyChildSchema(0); }
    inline void computeFlatSchema() override { copyChildSchema(0); }

    inline std::string getExpressionsForPrinting() const override { return nodeID->toString(); }

    inline std::shared_ptr<binder::Expression> getNodeID() const { return nodeID; }
    inline std::unordered_set<common::table_id_t> getTableIDSet() const { return tableIDSet; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalNodeLabelFilter>(nodeID, tableIDSet, children[0]->copy());
    }

private:
    std::shared_ptr<binder::Expression> nodeID;
    std::unordered_set<common::table_id_t> tableIDSet;
};

} // namespace planner
} // namespace lbug
