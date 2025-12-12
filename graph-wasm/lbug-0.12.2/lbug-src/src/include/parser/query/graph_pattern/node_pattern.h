#pragma once

#include <string>

#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

class NodePattern {
public:
    NodePattern(std::string name, std::vector<std::string> tableNames,
        std::vector<s_parsed_expr_pair> propertyKeyVals)
        : variableName{std::move(name)}, tableNames{std::move(tableNames)},
          propertyKeyVals{std::move(propertyKeyVals)} {}
    DELETE_COPY_DEFAULT_MOVE(NodePattern);

    virtual ~NodePattern() = default;

    inline std::string getVariableName() const { return variableName; }

    inline std::vector<std::string> getTableNames() const { return tableNames; }

    inline const std::vector<s_parsed_expr_pair>& getPropertyKeyVals() const {
        return propertyKeyVals;
    }

protected:
    std::string variableName;
    std::vector<std::string> tableNames;
    std::vector<s_parsed_expr_pair> propertyKeyVals;
};

} // namespace parser
} // namespace lbug
