#pragma once

#include "binder/query/query_graph.h"
#include "binder/query/reading_clause/bound_join_hint.h"
#include "common/enums/subquery_type.h"
#include "expression.h"

namespace lbug {
namespace binder {

class SubqueryExpression final : public Expression {
    static constexpr common::ExpressionType expressionType_ = common::ExpressionType::SUBQUERY;

public:
    SubqueryExpression(common::SubqueryType subqueryType, common::LogicalType dataType,
        QueryGraphCollection queryGraphCollection, std::string uniqueName, std::string rawName)
        : Expression{expressionType_, std::move(dataType), std::move(uniqueName)},
          subqueryType{subqueryType}, queryGraphCollection{std::move(queryGraphCollection)},
          rawName{std::move(rawName)} {}

    common::SubqueryType getSubqueryType() const { return subqueryType; }

    const QueryGraphCollection* getQueryGraphCollection() const { return &queryGraphCollection; }

    void setWhereExpression(std::shared_ptr<Expression> expression) {
        whereExpression = std::move(expression);
    }
    bool hasWhereExpression() const { return whereExpression != nullptr; }
    std::shared_ptr<Expression> getWhereExpression() const { return whereExpression; }
    expression_vector getPredicatesSplitOnAnd() const {
        return hasWhereExpression() ? whereExpression->splitOnAND() : expression_vector{};
    }

    void setCountStarExpr(std::shared_ptr<Expression> expr) { countStarExpr = std::move(expr); }
    std::shared_ptr<Expression> getCountStarExpr() const { return countStarExpr; }
    void setProjectionExpr(std::shared_ptr<Expression> expr) { projectionExpr = std::move(expr); }
    std::shared_ptr<Expression> getProjectionExpr() const { return projectionExpr; }

    void setHint(std::shared_ptr<BoundJoinHintNode> root) { hintRoot = std::move(root); }
    std::shared_ptr<BoundJoinHintNode> getHint() const { return hintRoot; }

    std::string toStringInternal() const override { return rawName; }

private:
    common::SubqueryType subqueryType;
    QueryGraphCollection queryGraphCollection;
    std::shared_ptr<Expression> whereExpression;
    std::shared_ptr<Expression> countStarExpr;
    std::shared_ptr<Expression> projectionExpr;
    std::shared_ptr<BoundJoinHintNode> hintRoot;
    std::string rawName;
};

} // namespace binder
} // namespace lbug
