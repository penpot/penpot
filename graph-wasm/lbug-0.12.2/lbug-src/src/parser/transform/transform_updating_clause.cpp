#include "common/assert.h"
#include "parser/query/updating_clause/delete_clause.h"
#include "parser/query/updating_clause/insert_clause.h"
#include "parser/query/updating_clause/merge_clause.h"
#include "parser/query/updating_clause/set_clause.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::unique_ptr<UpdatingClause> Transformer::transformUpdatingClause(
    CypherParser::OC_UpdatingClauseContext& ctx) {
    if (ctx.oC_Create()) {
        return transformCreate(*ctx.oC_Create());
    } else if (ctx.oC_Merge()) {
        return transformMerge(*ctx.oC_Merge());
    } else if (ctx.oC_Set()) {
        return transformSet(*ctx.oC_Set());
    } else {
        KU_ASSERT(ctx.oC_Delete());
        return transformDelete(*ctx.oC_Delete());
    }
}

std::unique_ptr<UpdatingClause> Transformer::transformCreate(CypherParser::OC_CreateContext& ctx) {
    return std::make_unique<InsertClause>(transformPattern(*ctx.oC_Pattern()));
}

std::unique_ptr<UpdatingClause> Transformer::transformMerge(CypherParser::OC_MergeContext& ctx) {
    auto mergeClause = std::make_unique<MergeClause>(transformPattern(*ctx.oC_Pattern()));
    for (auto& mergeActionCtx : ctx.oC_MergeAction()) {
        if (mergeActionCtx->MATCH()) {
            for (auto& setItemCtx : mergeActionCtx->oC_Set()->oC_SetItem()) {
                mergeClause->addOnMatchSetItems(transformSetItem(*setItemCtx));
            }
        } else {
            for (auto& setItemCtx : mergeActionCtx->oC_Set()->oC_SetItem()) {
                mergeClause->addOnCreateSetItems(transformSetItem(*setItemCtx));
            }
        }
    }
    return mergeClause;
}

std::unique_ptr<UpdatingClause> Transformer::transformSet(CypherParser::OC_SetContext& ctx) {
    auto setClause = std::make_unique<SetClause>();
    if (ctx.kU_Properties()) {
        auto child = transformAtom(*ctx.oC_Atom());
        for (auto i = 0u; i < ctx.kU_Properties()->oC_PropertyKeyName().size(); ++i) {
            auto propertyKeyName = createPropertyExpression(
                *ctx.kU_Properties()->oC_PropertyKeyName(i), child->copy());
            auto expression = transformExpression(*ctx.kU_Properties()->oC_Expression(i));
            setClause->addSetItem(make_pair(std::move(propertyKeyName), std::move(expression)));
        }
    } else {
        for (auto& setItem : ctx.oC_SetItem()) {
            setClause->addSetItem(transformSetItem(*setItem));
        }
    }
    return setClause;
}

parsed_expr_pair Transformer::transformSetItem(CypherParser::OC_SetItemContext& ctx) {
    return make_pair(transformProperty(*ctx.oC_PropertyExpression()),
        transformExpression(*ctx.oC_Expression()));
}

std::unique_ptr<UpdatingClause> Transformer::transformDelete(CypherParser::OC_DeleteContext& ctx) {
    auto deleteClauseType =
        ctx.DETACH() ? common::DeleteNodeType::DETACH_DELETE : common::DeleteNodeType::DELETE;
    auto deleteClause = std::make_unique<DeleteClause>(deleteClauseType);
    for (auto& expression : ctx.oC_Expression()) {
        deleteClause->addExpression(transformExpression(*expression));
    }
    return deleteClause;
}

} // namespace parser
} // namespace lbug
