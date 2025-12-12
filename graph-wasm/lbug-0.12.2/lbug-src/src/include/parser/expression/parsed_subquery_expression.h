#pragma once

#include "common/assert.h"
#include "common/enums/subquery_type.h"
#include "parsed_expression.h"
#include "parser/query/graph_pattern/pattern_element.h"
#include "parser/query/reading_clause/join_hint.h"

namespace lbug {
namespace parser {

class ParsedSubqueryExpression : public ParsedExpression {
    static constexpr common::ExpressionType type_ = common::ExpressionType::SUBQUERY;

public:
    ParsedSubqueryExpression(common::SubqueryType subqueryType, std::string rawName)
        : ParsedExpression{type_, std::move(rawName)}, subqueryType{subqueryType} {}

    common::SubqueryType getSubqueryType() const { return subqueryType; }

    void addPatternElement(PatternElement element) {
        patternElements.push_back(std::move(element));
    }
    void setPatternElements(std::vector<PatternElement> elements) {
        patternElements = std::move(elements);
    }
    const std::vector<PatternElement>& getPatternElements() const { return patternElements; }

    void setWhereClause(std::unique_ptr<ParsedExpression> expression) {
        whereClause = std::move(expression);
    }
    bool hasWhereClause() const { return whereClause != nullptr; }
    const ParsedExpression* getWhereClause() const { return whereClause.get(); }

    void setHint(std::shared_ptr<JoinHintNode> root) { hintRoot = std::move(root); }
    bool hasHint() const { return hintRoot != nullptr; }
    std::shared_ptr<JoinHintNode> getHint() const { return hintRoot; }

    static std::unique_ptr<ParsedSubqueryExpression> deserialize(common::Deserializer&) {
        KU_UNREACHABLE;
    }

    std::unique_ptr<ParsedExpression> copy() const override { KU_UNREACHABLE; }

private:
    void serializeInternal(common::Serializer&) const override { KU_UNREACHABLE; }

private:
    common::SubqueryType subqueryType;
    std::vector<PatternElement> patternElements;
    std::unique_ptr<ParsedExpression> whereClause;
    std::shared_ptr<JoinHintNode> hintRoot = nullptr;
};

} // namespace parser
} // namespace lbug
