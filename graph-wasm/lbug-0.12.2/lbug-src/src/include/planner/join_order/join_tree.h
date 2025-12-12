#pragma once

#include "binder/expression/node_expression.h"

namespace lbug {
namespace planner {

enum class TreeNodeType : uint8_t {
    NODE_SCAN = 0,
    REL_SCAN = 1,
    BINARY_JOIN = 5,
    MULTIWAY_JOIN = 6,
};

struct TreeNodeTypeUtils {
    static std::string toString(TreeNodeType type);
};

struct ExtraTreeNodeInfo {
    virtual ~ExtraTreeNodeInfo() = default;

    virtual std::unique_ptr<ExtraTreeNodeInfo> copy() const = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
};

struct ExtraJoinTreeNodeInfo : ExtraTreeNodeInfo {
    std::vector<std::shared_ptr<binder::NodeExpression>> joinNodes;
    binder::expression_vector predicates;

    explicit ExtraJoinTreeNodeInfo(std::shared_ptr<binder::NodeExpression> joinNode) {
        joinNodes.push_back(std::move(joinNode));
    }
    explicit ExtraJoinTreeNodeInfo(std::vector<std::shared_ptr<binder::NodeExpression>> joinNodes)
        : joinNodes{std::move(joinNodes)} {}
    ExtraJoinTreeNodeInfo(const ExtraJoinTreeNodeInfo& other)
        : joinNodes{other.joinNodes}, predicates{other.predicates} {}

    std::unique_ptr<ExtraTreeNodeInfo> copy() const override {
        return std::make_unique<ExtraJoinTreeNodeInfo>(*this);
    }
};

struct NodeRelScanInfo {
    std::shared_ptr<binder::Expression> nodeOrRel;
    binder::expression_vector properties;
    binder::expression_vector predicates;

    NodeRelScanInfo(std::shared_ptr<binder::Expression> nodeOrRel,
        binder::expression_vector properties)
        : nodeOrRel{std::move(nodeOrRel)}, properties{std::move(properties)} {}
};

struct ExtraScanTreeNodeInfo : ExtraTreeNodeInfo {
    std::unique_ptr<NodeRelScanInfo> nodeInfo;
    std::vector<NodeRelScanInfo> relInfos;
    binder::expression_vector predicates;

    ExtraScanTreeNodeInfo() = default;
    ExtraScanTreeNodeInfo(const ExtraScanTreeNodeInfo& other)
        : nodeInfo{std::make_unique<NodeRelScanInfo>(*other.nodeInfo)}, relInfos{other.relInfos} {}

    void merge(const ExtraScanTreeNodeInfo& other);

    std::unique_ptr<ExtraTreeNodeInfo> copy() const override {
        return std::make_unique<ExtraScanTreeNodeInfo>(*this);
    }
};

struct JoinTreeNode {
    TreeNodeType type;
    std::unique_ptr<ExtraTreeNodeInfo> extraInfo;
    std::vector<std::shared_ptr<JoinTreeNode>> children;

    JoinTreeNode(TreeNodeType type, std::unique_ptr<ExtraTreeNodeInfo> extraInfo)
        : type{type}, extraInfo{std::move(extraInfo)} {}
    DELETE_COPY_DEFAULT_MOVE(JoinTreeNode);

    std::string toString() const;

    void addChild(std::shared_ptr<JoinTreeNode> child) { children.push_back(std::move(child)); }
};

struct JoinTree {
    std::shared_ptr<JoinTreeNode> root;
    explicit JoinTree(std::shared_ptr<JoinTreeNode> root) : root{std::move(root)} {}

    JoinTree(const JoinTree& other) : root{other.root} {}
};

} // namespace planner
} // namespace lbug
