#include "parser/query/regular_query.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformQuery(CypherParser::OC_QueryContext& ctx) {
    return transformRegularQuery(*ctx.oC_RegularQuery());
}

std::unique_ptr<Statement> Transformer::transformRegularQuery(
    CypherParser::OC_RegularQueryContext& ctx) {
    auto regularQuery = std::make_unique<RegularQuery>(transformSingleQuery(*ctx.oC_SingleQuery()));
    for (auto unionClause : ctx.oC_Union()) {
        regularQuery->addSingleQuery(transformSingleQuery(*unionClause->oC_SingleQuery()),
            unionClause->ALL());
    }
    return regularQuery;
}

SingleQuery Transformer::transformSingleQuery(CypherParser::OC_SingleQueryContext& ctx) {
    auto singleQuery =
        ctx.oC_MultiPartQuery() ?
            transformSinglePartQuery(*ctx.oC_MultiPartQuery()->oC_SinglePartQuery()) :
            transformSinglePartQuery(*ctx.oC_SinglePartQuery());
    if (ctx.oC_MultiPartQuery()) {
        for (auto queryPart : ctx.oC_MultiPartQuery()->kU_QueryPart()) {
            singleQuery.addQueryPart(transformQueryPart(*queryPart));
        }
    }
    return singleQuery;
}

SingleQuery Transformer::transformSinglePartQuery(CypherParser::OC_SinglePartQueryContext& ctx) {
    auto singleQuery = SingleQuery();
    for (auto& readingClause : ctx.oC_ReadingClause()) {
        singleQuery.addReadingClause(transformReadingClause(*readingClause));
    }
    for (auto& updatingClause : ctx.oC_UpdatingClause()) {
        singleQuery.addUpdatingClause(transformUpdatingClause(*updatingClause));
    }
    if (ctx.oC_Return()) {
        singleQuery.setReturnClause(transformReturn(*ctx.oC_Return()));
    }
    return singleQuery;
}

QueryPart Transformer::transformQueryPart(CypherParser::KU_QueryPartContext& ctx) {
    auto queryPart = QueryPart(transformWith(*ctx.oC_With()));
    for (auto& readingClause : ctx.oC_ReadingClause()) {
        queryPart.addReadingClause(transformReadingClause(*readingClause));
    }
    for (auto& updatingClause : ctx.oC_UpdatingClause()) {
        queryPart.addUpdatingClause(transformUpdatingClause(*updatingClause));
    }
    return queryPart;
}

} // namespace parser
} // namespace lbug
