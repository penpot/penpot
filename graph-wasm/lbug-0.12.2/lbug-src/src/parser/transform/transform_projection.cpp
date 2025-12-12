#include "parser/query/return_with_clause/return_clause.h"
#include "parser/query/return_with_clause/with_clause.h"
#include "parser/transformer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

WithClause Transformer::transformWith(CypherParser::OC_WithContext& ctx) {
    auto withClause = WithClause(transformProjectionBody(*ctx.oC_ProjectionBody()));
    if (ctx.oC_Where()) {
        withClause.setWhereExpression(transformWhere(*ctx.oC_Where()));
    }
    return withClause;
}

ReturnClause Transformer::transformReturn(CypherParser::OC_ReturnContext& ctx) {
    return ReturnClause(transformProjectionBody(*ctx.oC_ProjectionBody()));
}

ProjectionBody Transformer::transformProjectionBody(CypherParser::OC_ProjectionBodyContext& ctx) {
    auto projectionBody = ProjectionBody(nullptr != ctx.DISTINCT(),
        transformProjectionItems(*ctx.oC_ProjectionItems()));
    if (ctx.oC_Order()) {
        std::vector<std::unique_ptr<ParsedExpression>> orderByExpressions;
        std::vector<bool> isAscOrders;
        for (auto& sortItem : ctx.oC_Order()->oC_SortItem()) {
            orderByExpressions.push_back(transformExpression(*sortItem->oC_Expression()));
            isAscOrders.push_back(!(sortItem->DESC() || sortItem->DESCENDING()));
        }
        projectionBody.setOrderByExpressions(std::move(orderByExpressions), std::move(isAscOrders));
    }
    if (ctx.oC_Skip()) {
        projectionBody.setSkipExpression(transformExpression(*ctx.oC_Skip()->oC_Expression()));
    }
    if (ctx.oC_Limit()) {
        projectionBody.setLimitExpression(transformExpression(*ctx.oC_Limit()->oC_Expression()));
    }
    return projectionBody;
}

std::vector<std::unique_ptr<ParsedExpression>> Transformer::transformProjectionItems(
    CypherParser::OC_ProjectionItemsContext& ctx) {
    std::vector<std::unique_ptr<ParsedExpression>> projectionExpressions;
    if (ctx.STAR()) {
        projectionExpressions.push_back(
            std::make_unique<ParsedExpression>(ExpressionType::STAR, ctx.STAR()->getText()));
    }
    for (auto& projectionItem : ctx.oC_ProjectionItem()) {
        projectionExpressions.push_back(transformProjectionItem(*projectionItem));
    }
    return projectionExpressions;
}

std::unique_ptr<ParsedExpression> Transformer::transformProjectionItem(
    CypherParser::OC_ProjectionItemContext& ctx) {
    auto expression = transformExpression(*ctx.oC_Expression());
    if (ctx.AS()) {
        expression->setAlias(transformVariable(*ctx.oC_Variable()));
    }
    return expression;
}

} // namespace parser
} // namespace lbug
