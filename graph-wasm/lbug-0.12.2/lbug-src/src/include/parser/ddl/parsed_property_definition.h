#pragma once

#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

struct ParsedColumnDefinition {
    std::string name;
    std::string type;

    ParsedColumnDefinition(std::string name, std::string type)
        : name{std::move(name)}, type{std::move(type)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ParsedColumnDefinition);

private:
    ParsedColumnDefinition(const ParsedColumnDefinition& other)
        : name{other.name}, type{other.type} {}
};

struct ParsedPropertyDefinition {
    ParsedColumnDefinition columnDefinition;
    std::unique_ptr<ParsedExpression> defaultExpr;

    ParsedPropertyDefinition(ParsedColumnDefinition columnDefinition,
        std::unique_ptr<ParsedExpression> defaultExpr)
        : columnDefinition{std::move(columnDefinition)}, defaultExpr{std::move(defaultExpr)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ParsedPropertyDefinition);

    std::string getName() const { return columnDefinition.name; }
    std::string getType() const { return columnDefinition.type; }

private:
    ParsedPropertyDefinition(const ParsedPropertyDefinition& other)
        : columnDefinition{other.columnDefinition.copy()} {
        if (other.defaultExpr) {
            defaultExpr = other.defaultExpr->copy();
        }
    }
};

} // namespace parser
} // namespace lbug
