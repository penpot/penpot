#pragma once

#include "common/copy_constructors.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

class ProjectionBody {
public:
    ProjectionBody(bool isDistinct,
        std::vector<std::unique_ptr<ParsedExpression>> projectionExpressions)
        : isDistinct{isDistinct}, projectionExpressions{std::move(projectionExpressions)} {}
    DELETE_COPY_DEFAULT_MOVE(ProjectionBody);

    inline bool getIsDistinct() const { return isDistinct; }

    inline const std::vector<std::unique_ptr<ParsedExpression>>& getProjectionExpressions() const {
        return projectionExpressions;
    }

    inline void setOrderByExpressions(std::vector<std::unique_ptr<ParsedExpression>> expressions,
        std::vector<bool> sortOrders) {
        orderByExpressions = std::move(expressions);
        isAscOrders = std::move(sortOrders);
    }
    inline bool hasOrderByExpressions() const { return !orderByExpressions.empty(); }
    inline const std::vector<std::unique_ptr<ParsedExpression>>& getOrderByExpressions() const {
        return orderByExpressions;
    }

    inline std::vector<bool> getSortOrders() const { return isAscOrders; }

    inline void setSkipExpression(std::unique_ptr<ParsedExpression> expression) {
        skipExpression = std::move(expression);
    }
    inline bool hasSkipExpression() const { return skipExpression != nullptr; }
    inline ParsedExpression* getSkipExpression() const { return skipExpression.get(); }

    inline void setLimitExpression(std::unique_ptr<ParsedExpression> expression) {
        limitExpression = std::move(expression);
    }
    inline bool hasLimitExpression() const { return limitExpression != nullptr; }
    inline ParsedExpression* getLimitExpression() const { return limitExpression.get(); }

private:
    bool isDistinct;
    std::vector<std::unique_ptr<ParsedExpression>> projectionExpressions;
    std::vector<std::unique_ptr<ParsedExpression>> orderByExpressions;
    std::vector<bool> isAscOrders;
    std::unique_ptr<ParsedExpression> skipExpression;
    std::unique_ptr<ParsedExpression> limitExpression;
};

} // namespace parser
} // namespace lbug
