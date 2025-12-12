#include "binder/visitor/property_collector.h"

#include "binder/expression/expression_util.h"
#include "binder/expression_visitor.h"
#include "binder/query/reading_clause/bound_load_from.h"
#include "binder/query/reading_clause/bound_match_clause.h"
#include "binder/query/reading_clause/bound_table_function_call.h"
#include "binder/query/reading_clause/bound_unwind_clause.h"
#include "binder/query/updating_clause/bound_delete_clause.h"
#include "binder/query/updating_clause/bound_insert_clause.h"
#include "binder/query/updating_clause/bound_merge_clause.h"
#include "binder/query/updating_clause/bound_set_clause.h"
#include "catalog/catalog_entry/table_catalog_entry.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

expression_vector PropertyCollector::getProperties() const {
    expression_vector result;
    for (auto& property : properties) {
        result.push_back(property);
    }
    return result;
}

void PropertyCollector::visitSingleQuerySkipNodeRel(const NormalizedSingleQuery& singleQuery) {
    KU_ASSERT(singleQuery.getNumQueryParts() != 0);
    auto numQueryParts = singleQuery.getNumQueryParts();
    for (auto i = 0u; i < numQueryParts - 1; ++i) {
        visitQueryPartSkipNodeRel(*singleQuery.getQueryPart(i));
    }
    visitQueryPart(*singleQuery.getQueryPart(numQueryParts - 1));
}

void PropertyCollector::visitQueryPartSkipNodeRel(const NormalizedQueryPart& queryPart) {
    for (auto i = 0u; i < queryPart.getNumReadingClause(); ++i) {
        visitReadingClause(*queryPart.getReadingClause(i));
    }
    for (auto i = 0u; i < queryPart.getNumUpdatingClause(); ++i) {
        visitUpdatingClause(*queryPart.getUpdatingClause(i));
    }
    if (queryPart.hasProjectionBody()) {
        visitProjectionBodySkipNodeRel(*queryPart.getProjectionBody());
        if (queryPart.hasProjectionBodyPredicate()) {
            visitProjectionBodyPredicate(queryPart.getProjectionBodyPredicate());
        }
    }
}

void PropertyCollector::visitMatch(const BoundReadingClause& readingClause) {
    auto& matchClause = readingClause.constCast<BoundMatchClause>();
    if (matchClause.hasPredicate()) {
        collectProperties(matchClause.getPredicate());
    }
}

void PropertyCollector::visitUnwind(const BoundReadingClause& readingClause) {
    auto& unwindClause = readingClause.constCast<BoundUnwindClause>();
    collectProperties(unwindClause.getInExpr());
}

void PropertyCollector::visitLoadFrom(const BoundReadingClause& readingClause) {
    auto& loadFromClause = readingClause.constCast<BoundLoadFrom>();
    if (loadFromClause.hasPredicate()) {
        collectProperties(loadFromClause.getPredicate());
    }
}

void PropertyCollector::visitTableFunctionCall(const BoundReadingClause& readingClause) {
    auto& call = readingClause.constCast<BoundTableFunctionCall>();
    if (call.hasPredicate()) {
        collectProperties(call.getPredicate());
    }
}

void PropertyCollector::visitSet(const BoundUpdatingClause& updatingClause) {
    auto& boundSetClause = updatingClause.constCast<BoundSetClause>();
    for (auto& info : boundSetClause.getInfos()) {
        collectProperties(info.columnData);
    }
    for (const auto& info : boundSetClause.getRelInfos()) {
        auto& rel = info.pattern->constCast<RelExpression>();
        KU_ASSERT(!rel.isEmpty() && rel.getRelType() == QueryRelType::NON_RECURSIVE);
        properties.insert(rel.getInternalID());
    }
}

void PropertyCollector::visitDelete(const BoundUpdatingClause& updatingClause) {
    auto& boundDeleteClause = updatingClause.constCast<BoundDeleteClause>();
    // Read primary key if we are deleting nodes;
    for (const auto& info : boundDeleteClause.getNodeInfos()) {
        auto& node = info.pattern->constCast<NodeExpression>();
        for (const auto entry : node.getEntries()) {
            properties.insert(node.getPrimaryKey(entry->getTableID()));
        }
    }
    // Read rel internal id if we are deleting relationships.
    for (const auto& info : boundDeleteClause.getRelInfos()) {
        auto& rel = info.pattern->constCast<RelExpression>();
        if (!rel.isEmpty() && rel.getRelType() == QueryRelType::NON_RECURSIVE) {
            properties.insert(rel.getInternalID());
        }
    }
}

void PropertyCollector::visitInsert(const BoundUpdatingClause& updatingClause) {
    auto& insertClause = updatingClause.constCast<BoundInsertClause>();
    for (auto& info : insertClause.getInfos()) {
        for (auto& expr : info.columnDataExprs) {
            collectProperties(expr);
        }
    }
}

void PropertyCollector::visitMerge(const BoundUpdatingClause& updatingClause) {
    auto& boundMergeClause = updatingClause.constCast<BoundMergeClause>();
    for (auto& rel : boundMergeClause.getQueryGraphCollection()->getQueryRels()) {
        if (rel->getRelType() == QueryRelType::NON_RECURSIVE) {
            properties.insert(rel->getInternalID());
        }
    }
    if (boundMergeClause.hasPredicate()) {
        collectProperties(boundMergeClause.getPredicate());
    }
    for (auto& info : boundMergeClause.getInsertInfosRef()) {
        for (auto& expr : info.columnDataExprs) {
            collectProperties(expr);
        }
    }
    for (auto& info : boundMergeClause.getOnMatchSetInfosRef()) {
        collectProperties(info.columnData);
    }
    for (auto& info : boundMergeClause.getOnCreateSetInfosRef()) {
        collectProperties(info.columnData);
    }
}

void PropertyCollector::visitProjectionBodySkipNodeRel(const BoundProjectionBody& projectionBody) {
    for (auto& expression : projectionBody.getProjectionExpressions()) {
        collectPropertiesSkipNodeRel(expression);
    }
    for (auto& expression : projectionBody.getOrderByExpressions()) {
        collectPropertiesSkipNodeRel(expression);
    }
}

void PropertyCollector::visitProjectionBody(const BoundProjectionBody& projectionBody) {
    for (auto& expression : projectionBody.getProjectionExpressions()) {
        collectProperties(expression);
    }
    for (auto& expression : projectionBody.getOrderByExpressions()) {
        collectProperties(expression);
    }
}

void PropertyCollector::visitProjectionBodyPredicate(const std::shared_ptr<Expression>& predicate) {
    collectProperties(predicate);
}

void PropertyCollector::collectProperties(const std::shared_ptr<Expression>& expression) {
    auto collector = PropertyExprCollector();
    collector.visit(expression);
    for (auto& expr : collector.getPropertyExprs()) {
        properties.insert(expr);
    }
}

void PropertyCollector::collectPropertiesSkipNodeRel(
    const std::shared_ptr<Expression>& expression) {
    if (ExpressionUtil::isNodePattern(*expression) || ExpressionUtil::isRelPattern(*expression) ||
        ExpressionUtil::isRecursiveRelPattern(*expression)) {
        return;
    }
    collectProperties(expression);
}

} // namespace binder
} // namespace lbug
