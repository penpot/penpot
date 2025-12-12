#pragma once

#include "common/constants.h"
#include "common/enums/extend_direction.h"
#include "common/enums/query_rel_type.h"
#include "function/gds/rec_joins.h"
#include "node_expression.h"

namespace lbug {
namespace binder {

enum class RelDirectionType : uint8_t {
    SINGLE = 0,
    BOTH = 1,
    UNKNOWN = 2,
};

class RelExpression;

struct RecursiveInfo {
    /*
     * E.g. [e*1..2 (r, n) | WHERE n.age > 10 AND r.year = 2012 ]
     * node = n
     * nodeCopy = n (see comment below)
     * rel = r
     * predicates = [n.age > 10, r.year = 2012]
     * */
    std::shared_ptr<NodeExpression> node = nullptr;
    // NodeCopy has the same fields as node but a different unique name.
    // We use nodeCopy to plan recursive plan because boundNode&nbrNode cannot be the same.
    std::shared_ptr<NodeExpression> nodeCopy = nullptr;
    std::shared_ptr<RelExpression> rel = nullptr;
    // Predicates
    std::shared_ptr<Expression> nodePredicate = nullptr;
    std::shared_ptr<Expression> relPredicate = nullptr;
    // Projection list
    expression_vector nodeProjectionList;
    expression_vector relProjectionList;
    // Function information
    std::unique_ptr<function::RJAlgorithm> function;
    std::unique_ptr<function::RJBindData> bindData;
};

class LBUG_API RelExpression final : public NodeOrRelExpression {
public:
    RelExpression(common::LogicalType dataType, std::string uniqueName, std::string variableName,
        std::vector<catalog::TableCatalogEntry*> entries, std::shared_ptr<NodeExpression> srcNode,
        std::shared_ptr<NodeExpression> dstNode, RelDirectionType directionType,
        common::QueryRelType relType)
        : NodeOrRelExpression{std::move(dataType), std::move(uniqueName), std::move(variableName),
              std::move(entries)},
          srcNode{std::move(srcNode)}, dstNode{std::move(dstNode)}, directionType{directionType},
          relType{relType} {}

    bool isRecursive() const {
        return dataType.getLogicalTypeID() == common::LogicalTypeID::RECURSIVE_REL;
    }

    bool isMultiLabeled() const override;
    bool isBoundByMultiLabeledNode() const {
        return srcNode->isMultiLabeled() || dstNode->isMultiLabeled();
    }

    std::shared_ptr<NodeExpression> getSrcNode() const { return srcNode; }
    std::string getSrcNodeName() const { return srcNode->getUniqueName(); }
    void setDstNode(std::shared_ptr<NodeExpression> node) { dstNode = std::move(node); }
    std::shared_ptr<NodeExpression> getDstNode() const { return dstNode; }
    std::string getDstNodeName() const { return dstNode->getUniqueName(); }

    void setLeftNode(std::shared_ptr<NodeExpression> node) { leftNode = std::move(node); }
    std::shared_ptr<NodeExpression> getLeftNode() const { return leftNode; }
    void setRightNode(std::shared_ptr<NodeExpression> node) { rightNode = std::move(node); }
    std::shared_ptr<NodeExpression> getRightNode() const { return rightNode; }

    common::QueryRelType getRelType() const { return relType; }

    void setDirectionExpr(std::shared_ptr<Expression> expr) { directionExpr = std::move(expr); }
    bool hasDirectionExpr() const { return directionExpr != nullptr; }
    std::shared_ptr<Expression> getDirectionExpr() const { return directionExpr; }
    RelDirectionType getDirectionType() const { return directionType; }

    std::shared_ptr<PropertyExpression> getInternalID() const override {
        return getPropertyExpression(common::InternalKeyword::ID);
    }

    void setRecursiveInfo(std::unique_ptr<RecursiveInfo> recursiveInfo_) {
        recursiveInfo = std::move(recursiveInfo_);
    }
    const RecursiveInfo* getRecursiveInfo() const { return recursiveInfo.get(); }
    std::shared_ptr<Expression> getLengthExpression() const {
        KU_ASSERT(recursiveInfo != nullptr);
        return recursiveInfo->bindData->lengthExpr;
    }

    bool isSelfLoop() const { return *srcNode == *dstNode; }

    std::string detailsToString() const;

    // if multiple tables match the pattern
    // returns the intersection of available extend directions for all matched tables
    std::vector<common::ExtendDirection> getExtendDirections() const;

    std::vector<common::table_id_t> getInnerRelTableIDs() const;

private:
    // Start node if a directed arrow is given. Left node otherwise.
    std::shared_ptr<NodeExpression> srcNode;
    // End node if a directed arrow is given. Right node otherwise.
    std::shared_ptr<NodeExpression> dstNode;
    std::shared_ptr<NodeExpression> leftNode;
    std::shared_ptr<NodeExpression> rightNode;
    // Whether relationship is directed.
    RelDirectionType directionType;
    // Direction expr is nullptr when direction type is SINGLE
    std::shared_ptr<Expression> directionExpr;
    // Whether relationship type is recursive.
    common::QueryRelType relType;
    // Null if relationship type is non-recursive.
    std::unique_ptr<RecursiveInfo> recursiveInfo;
};

} // namespace binder
} // namespace lbug
