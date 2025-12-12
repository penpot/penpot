#pragma once

#include "binder/expression/expression.h"

namespace lbug {
namespace binder {

class PathExpression final : public Expression {
public:
    PathExpression(common::LogicalType dataType, std::string uniqueName, std::string variableName,
        common::LogicalType nodeType, common::LogicalType relType, expression_vector children)
        : Expression{common::ExpressionType::PATH, std::move(dataType), std::move(children),
              std::move(uniqueName)},
          variableName{std::move(variableName)}, nodeType{std::move(nodeType)},
          relType{std::move(relType)} {}

    std::string getVariableName() const { return variableName; }
    const common::LogicalType& getNodeType() const { return nodeType; }
    const common::LogicalType& getRelType() const { return relType; }

    std::string toStringInternal() const override { return variableName; }

private:
    std::string variableName;
    common::LogicalType nodeType;
    common::LogicalType relType;
};

} // namespace binder
} // namespace lbug
