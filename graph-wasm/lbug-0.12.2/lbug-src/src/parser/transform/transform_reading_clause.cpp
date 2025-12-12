#include "common/assert.h"
#include "parser/query/reading_clause/in_query_call_clause.h"
#include "parser/query/reading_clause/load_from.h"
#include "parser/query/reading_clause/match_clause.h"
#include "parser/query/reading_clause/unwind_clause.h"
#include "parser/transformer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

std::unique_ptr<ReadingClause> Transformer::transformReadingClause(
    CypherParser::OC_ReadingClauseContext& ctx) {
    if (ctx.oC_Match()) {
        return transformMatch(*ctx.oC_Match());
    } else if (ctx.oC_Unwind()) {
        return transformUnwind(*ctx.oC_Unwind());
    } else if (ctx.kU_InQueryCall()) {
        return transformInQueryCall(*ctx.kU_InQueryCall());
    } else if (ctx.kU_LoadFrom()) {
        return transformLoadFrom(*ctx.kU_LoadFrom());
    }
    KU_UNREACHABLE;
}

std::unique_ptr<ReadingClause> Transformer::transformMatch(CypherParser::OC_MatchContext& ctx) {
    auto matchClauseType =
        ctx.OPTIONAL() ? MatchClauseType::OPTIONAL_MATCH : MatchClauseType::MATCH;
    auto matchClause =
        std::make_unique<MatchClause>(transformPattern(*ctx.oC_Pattern()), matchClauseType);
    if (ctx.oC_Where()) {
        matchClause->setWherePredicate(transformWhere(*ctx.oC_Where()));
    }
    if (ctx.kU_Hint()) {
        matchClause->setHint(transformJoinHint(*ctx.kU_Hint()->kU_JoinNode()));
    }
    return matchClause;
}

std::shared_ptr<JoinHintNode> Transformer::transformJoinHint(
    CypherParser::KU_JoinNodeContext& ctx) {
    if (!ctx.MULTI_JOIN().empty()) {
        auto joinNode = std::make_shared<JoinHintNode>();
        joinNode->addChild(transformJoinHint(*ctx.kU_JoinNode(0)));
        for (auto& schemaNameCtx : ctx.oC_SchemaName()) {
            joinNode->addChild(std::make_shared<JoinHintNode>(transformSchemaName(*schemaNameCtx)));
        }
        return joinNode;
    }
    if (!ctx.oC_SchemaName().empty()) {
        return std::make_shared<JoinHintNode>(transformSchemaName(*ctx.oC_SchemaName(0)));
    }
    if (ctx.kU_JoinNode().size() == 1) {
        return transformJoinHint(*ctx.kU_JoinNode(0));
    }
    KU_ASSERT(ctx.kU_JoinNode().size() == 2);
    auto joinNode = std::make_shared<JoinHintNode>();
    joinNode->addChild(transformJoinHint(*ctx.kU_JoinNode(0)));
    joinNode->addChild(transformJoinHint(*ctx.kU_JoinNode(1)));
    return joinNode;
}

std::unique_ptr<ReadingClause> Transformer::transformUnwind(CypherParser::OC_UnwindContext& ctx) {
    auto expression = transformExpression(*ctx.oC_Expression());
    auto transformedVariable = transformVariable(*ctx.oC_Variable());
    return std::make_unique<UnwindClause>(std::move(expression), std::move(transformedVariable));
}

std::vector<YieldVariable> Transformer::transformYieldVariables(
    CypherParser::OC_YieldItemsContext& ctx) {
    std::vector<YieldVariable> yieldVariables;
    std::string name;
    for (auto& yieldItem : ctx.oC_YieldItem()) {
        std::string alias;
        if (yieldItem->AS()) {
            alias = transformVariable(*yieldItem->oC_Variable(1));
        }
        name = transformVariable(*yieldItem->oC_Variable(0));
        yieldVariables.emplace_back(name, alias);
    }
    return yieldVariables;
}

std::unique_ptr<ReadingClause> Transformer::transformInQueryCall(
    CypherParser::KU_InQueryCallContext& ctx) {
    auto functionExpression =
        Transformer::transformFunctionInvocation(*ctx.oC_FunctionInvocation());
    std::vector<YieldVariable> yieldVariables;
    if (ctx.oC_YieldItems()) {
        yieldVariables = transformYieldVariables(*ctx.oC_YieldItems());
    }
    auto inQueryCall = std::make_unique<InQueryCallClause>(std::move(functionExpression),
        std::move(yieldVariables));
    if (ctx.oC_Where()) {
        inQueryCall->setWherePredicate(transformWhere(*ctx.oC_Where()));
    }
    return inQueryCall;
}

std::unique_ptr<ReadingClause> Transformer::transformLoadFrom(
    CypherParser::KU_LoadFromContext& ctx) {
    auto source = transformScanSource(*ctx.kU_ScanSource());
    auto loadFrom = std::make_unique<LoadFrom>(std::move(source));
    if (ctx.kU_ColumnDefinitions()) {
        loadFrom->setPropertyDefinitions(transformColumnDefinitions(*ctx.kU_ColumnDefinitions()));
    }
    if (ctx.kU_Options()) {
        loadFrom->setParingOptions(transformOptions(*ctx.kU_Options()));
    }
    if (ctx.oC_Where()) {
        loadFrom->setWherePredicate(transformWhere(*ctx.oC_Where()));
    }
    return loadFrom;
}

} // namespace parser
} // namespace lbug
