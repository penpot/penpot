#pragma once

#include <memory>
#include <vector>

namespace lbug {
namespace parser {

struct JoinHintNode {
    std::string variableName;
    std::vector<std::shared_ptr<JoinHintNode>> children;

    JoinHintNode() = default;
    explicit JoinHintNode(std::string name) : variableName{std::move(name)} {}
    void addChild(std::shared_ptr<JoinHintNode> child) { children.push_back(std::move(child)); }
    bool isLeaf() const { return children.empty(); }
};

} // namespace parser
} // namespace lbug
