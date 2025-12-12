#pragma once

#include "logical_operator_visitor.h"

namespace lbug {
namespace optimizer {

class LogicalOperatorCollector : public LogicalOperatorVisitor {
public:
    ~LogicalOperatorCollector() override = default;

    void collect(planner::LogicalOperator* op);

    bool hasOperators() const { return !ops.empty(); }
    const std::vector<planner::LogicalOperator*>& getOperators() const { return ops; }

protected:
    std::vector<planner::LogicalOperator*> ops;
};

class LogicalFlattenCollector final : public LogicalOperatorCollector {
protected:
    void visitFlatten(planner::LogicalOperator* op) override { ops.push_back(op); }
};

class LogicalFilterCollector final : public LogicalOperatorCollector {
protected:
    void visitFilter(planner::LogicalOperator* op) override { ops.push_back(op); }
};

class LogicalScanNodeTableCollector final : public LogicalOperatorCollector {
protected:
    void visitScanNodeTable(planner::LogicalOperator* op) override { ops.push_back(op); }
};

// TODO(Xiyang): Rename me.
class LogicalIndexScanNodeCollector final : public LogicalOperatorCollector {
protected:
    void visitScanNodeTable(planner::LogicalOperator* op) override;
};

class LogicalRecursiveExtendCollector final : public LogicalOperatorCollector {
protected:
    void visitRecursiveExtend(planner::LogicalOperator* op) override { ops.push_back(op); }
};

} // namespace optimizer
} // namespace lbug
