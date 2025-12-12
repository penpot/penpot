#pragma once

#include "common/copy_constructors.h"
#include "common/enums/query_rel_type.h"
#include "node_pattern.h"

namespace lbug {
namespace parser {

enum class ArrowDirection : uint8_t { LEFT = 0, RIGHT = 1, BOTH = 2 };

struct RecursiveRelPatternInfo {
    std::string lowerBound;
    std::string upperBound;
    std::string weightPropertyName;
    std::string relName;
    std::string nodeName;
    std::unique_ptr<ParsedExpression> whereExpression = nullptr;
    bool hasProjection = false;
    parsed_expr_vector relProjectionList;
    parsed_expr_vector nodeProjectionList;

    RecursiveRelPatternInfo() = default;
    DELETE_COPY_DEFAULT_MOVE(RecursiveRelPatternInfo);
};

class RelPattern : public NodePattern {
public:
    RelPattern(std::string name, std::vector<std::string> tableNames, common::QueryRelType relType,
        ArrowDirection arrowDirection, std::vector<s_parsed_expr_pair> propertyKeyValPairs,
        RecursiveRelPatternInfo recursiveInfo)
        : NodePattern{std::move(name), std::move(tableNames), std::move(propertyKeyValPairs)},
          relType{relType}, arrowDirection{arrowDirection},
          recursiveInfo{std::move(recursiveInfo)} {}
    DELETE_COPY_DEFAULT_MOVE(RelPattern);

    common::QueryRelType getRelType() const { return relType; }

    ArrowDirection getDirection() const { return arrowDirection; }

    const RecursiveRelPatternInfo* getRecursiveInfo() const { return &recursiveInfo; }

private:
    common::QueryRelType relType;
    ArrowDirection arrowDirection;
    RecursiveRelPatternInfo recursiveInfo;
};

} // namespace parser
} // namespace lbug
