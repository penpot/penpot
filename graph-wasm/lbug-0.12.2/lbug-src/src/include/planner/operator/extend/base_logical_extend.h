#pragma once

#include "binder/expression/rel_expression.h"
#include "common/enums/extend_direction.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct BaseLogicalExtendPrintInfo : OPPrintInfo {
    // Start node of extension.
    std::shared_ptr<binder::NodeExpression> boundNode;
    // End node of extension.
    std::shared_ptr<binder::NodeExpression> nbrNode;
    std::shared_ptr<binder::RelExpression> rel;
    common::ExtendDirection direction;

    BaseLogicalExtendPrintInfo(std::shared_ptr<binder::NodeExpression> boundNode,
        std::shared_ptr<binder::NodeExpression> nbrNode, std::shared_ptr<binder::RelExpression> rel,
        common::ExtendDirection direction)
        : boundNode{std::move(boundNode)}, nbrNode{std::move(nbrNode)}, rel{std::move(rel)},
          direction{direction} {}

    std::string toString() const override {
        switch (direction) {
        case common::ExtendDirection::FWD: {
            return "(" + boundNode->toString() + ")-[" + rel->toString() + "]->(" +
                   nbrNode->toString() + ")";
        }
        case common::ExtendDirection::BWD: {
            return "(" + nbrNode->toString() + ")-[" + rel->toString() + "]->(" +
                   boundNode->toString() + ")";
        }
        case common::ExtendDirection::BOTH: {
            return "(" + boundNode->toString() + ")-[" + rel->toString() + "]-(" +
                   nbrNode->toString() + ")";
        }
        default: {
            KU_UNREACHABLE;
        }
        }
    }
};

class BaseLogicalExtend : public LogicalOperator {
public:
    BaseLogicalExtend(LogicalOperatorType operatorType,
        std::shared_ptr<binder::NodeExpression> boundNode,
        std::shared_ptr<binder::NodeExpression> nbrNode, std::shared_ptr<binder::RelExpression> rel,
        common::ExtendDirection direction, bool extendFromSource_,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{operatorType, std::move(child)}, boundNode{std::move(boundNode)},
          nbrNode{std::move(nbrNode)}, rel{std::move(rel)}, direction{direction},
          extendFromSource_{extendFromSource_} {}

    std::shared_ptr<binder::NodeExpression> getBoundNode() const { return boundNode; }
    std::shared_ptr<binder::NodeExpression> getNbrNode() const { return nbrNode; }
    std::shared_ptr<binder::RelExpression> getRel() const { return rel; }
    bool isRecursive() const { return rel->isRecursive(); }
    common::ExtendDirection getDirection() const { return direction; }

    bool extendFromSourceNode() const { return extendFromSource_; }

    virtual f_group_pos_set getGroupsPosToFlatten() = 0;

    std::string getExpressionsForPrinting() const override;

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<BaseLogicalExtendPrintInfo>(boundNode, nbrNode, rel, direction);
    }

protected:
    // Start node of extension.
    std::shared_ptr<binder::NodeExpression> boundNode;
    // End node of extension.
    std::shared_ptr<binder::NodeExpression> nbrNode;
    std::shared_ptr<binder::RelExpression> rel;
    common::ExtendDirection direction;
    // Ideally we should check this by *boundNode == *rel->getSrcNode()
    // This is currently not doable due to recursive plan not setting src node correctly.
    bool extendFromSource_;
};

} // namespace planner
} // namespace lbug
