#include "planner/operator/factorization/flatten_resolver.h"

#include "binder/expression/case_expression.h"
#include "binder/expression/lambda_expression.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression/subquery_expression.h"
#include "common/exception/not_implemented.h"
#include "planner/operator/schema.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace planner {

std::pair<f_group_pos, f_group_pos_set> FlattenAllButOne::getGroupsPosToFlatten(
    const expression_vector& exprs, const Schema& schema) {
    f_group_pos_set result;
    f_group_pos_set dependentGroups;
    for (auto expr : exprs) {
        auto analyzer = GroupDependencyAnalyzer(false /* collectDependentExpr */, schema);
        analyzer.visit(expr);
        for (auto pos : analyzer.getRequiredFlatGroups()) {
            result.insert(pos);
        }
        for (auto pos : analyzer.getDependentGroups()) {
            dependentGroups.insert(pos);
        }
    }
    std::vector<f_group_pos> candidates;
    for (auto pos : dependentGroups) {
        if (!schema.getGroup(pos)->isFlat() && !result.contains(pos)) {
            candidates.push_back(pos);
        }
    }
    for (auto i = 1u; i < candidates.size(); ++i) {
        result.insert(candidates[i]);
    }
    if (candidates.empty()) {
        return std::make_pair(INVALID_F_GROUP_POS, result);
    } else {
        return std::make_pair(candidates[0], result);
    }
}

f_group_pos_set FlattenAllButOne::getGroupsPosToFlatten(std::shared_ptr<Expression> expr,
    const Schema& schema) {
    auto analyzer = GroupDependencyAnalyzer(false /* collectDependentExpr */, schema);
    analyzer.visit(expr);
    f_group_pos_set result = analyzer.getRequiredFlatGroups();
    std::vector<f_group_pos> candidates;
    for (auto groupPos : analyzer.getDependentGroups()) {
        if (!schema.getGroup(groupPos)->isFlat() && !result.contains(groupPos)) {
            candidates.push_back(groupPos);
        }
    }
    // Keep the first group as unFlat.
    for (auto i = 1u; i < candidates.size(); ++i) {
        result.insert(candidates[i]);
    }
    return result;
}

f_group_pos_set FlattenAllButOne::getGroupsPosToFlatten(
    const std::unordered_set<f_group_pos>& dependentGroups, const Schema& schema) {
    f_group_pos_set result;
    std::vector<f_group_pos> candidates;
    for (auto groupPos : dependentGroups) {
        if (!schema.getGroup(groupPos)->isFlat()) {
            candidates.push_back(groupPos);
        }
    }
    for (auto i = 1u; i < candidates.size(); ++i) {
        result.insert(candidates[i]);
    }
    return result;
}

f_group_pos_set FlattenAll::getGroupsPosToFlatten(const expression_vector& exprs,
    const Schema& schema) {
    f_group_pos_set result;
    for (auto& expr : exprs) {
        for (auto pos : getGroupsPosToFlatten(expr, schema)) {
            result.insert(pos);
        }
    }
    return result;
}

f_group_pos_set FlattenAll::getGroupsPosToFlatten(std::shared_ptr<Expression> expr,
    const Schema& schema) {
    auto analyzer = GroupDependencyAnalyzer(false /* collectDependentExpr */, schema);
    analyzer.visit(expr);
    return getGroupsPosToFlatten(analyzer.getDependentGroups(), schema);
}

f_group_pos_set FlattenAll::getGroupsPosToFlatten(
    const std::unordered_set<f_group_pos>& dependentGroups, const Schema& schema) {
    f_group_pos_set result;
    for (auto groupPos : dependentGroups) {
        if (!schema.getGroup(groupPos)->isFlat()) {
            result.insert(groupPos);
        }
    }
    return result;
}

void GroupDependencyAnalyzer::visit(std::shared_ptr<binder::Expression> expr) {
    if (schema.isExpressionInScope(*expr)) {
        dependentGroups.insert(schema.getGroupPos(*expr));
        if (collectDependentExpr) {
            dependentExprs.insert(expr);
        }
        return;
    }
    switch (expr->expressionType) {
    case ExpressionType::FUNCTION:
        return visitFunction(expr);
    case ExpressionType::CASE_ELSE: {
        visitCase(expr);
    } break;
    case ExpressionType::PATTERN: {
        visitNodeOrRel(expr);
    } break;
    case ExpressionType::SUBQUERY: {
        visitSubquery(expr);
    } break;
    case ExpressionType::LAMBDA: {
        visit(expr->constCast<LambdaExpression>().getFunctionExpr());
    } break;
    case ExpressionType::LITERAL:
    case ExpressionType::AGGREGATE_FUNCTION:
    case ExpressionType::PROPERTY:
    case ExpressionType::VARIABLE:
    case ExpressionType::PATH:
    case ExpressionType::PARAMETER:
    case ExpressionType::GRAPH:
    case ExpressionType::OR:
    case ExpressionType::XOR:
    case ExpressionType::AND:
    case ExpressionType::NOT:
    case ExpressionType::EQUALS:
    case ExpressionType::NOT_EQUALS:
    case ExpressionType::GREATER_THAN:
    case ExpressionType::GREATER_THAN_EQUALS:
    case ExpressionType::LESS_THAN:
    case ExpressionType::LESS_THAN_EQUALS:
    case ExpressionType::IS_NULL:
    case ExpressionType::IS_NOT_NULL: {
        for (auto& child : expr->getChildren()) {
            visit(child);
        }
    } break;
        // LCOV_EXCL_START
    default:
        throw NotImplementedException("GroupDependencyAnalyzer::visit");
        // LCOV_EXCL_STOP
    }
}

void GroupDependencyAnalyzer::visitFunction(std::shared_ptr<binder::Expression> expr) {
    auto& funcExpr = expr->constCast<ScalarFunctionExpression>();
    for (auto& child : expr->getChildren()) {
        visit(child);
    }
    // For list lambda we need to flatten all dependent expressions in lambda function
    // E.g. MATCH (a)->(b) RETURN list_filter(a.list, x -> x>b.age)
    if (funcExpr.getFunction().isListLambda) {
        auto lambdaFunctionAnalyzer = GroupDependencyAnalyzer(collectDependentExpr, schema);
        lambdaFunctionAnalyzer.visit(funcExpr.getChild(1));
        requiredFlatGroups = lambdaFunctionAnalyzer.getDependentGroups();
    }
}

void GroupDependencyAnalyzer::visitCase(std::shared_ptr<binder::Expression> expr) {
    auto& caseExpression = expr->constCast<CaseExpression>();
    for (auto i = 0u; i < caseExpression.getNumCaseAlternatives(); ++i) {
        auto caseAlternative = caseExpression.getCaseAlternative(i);
        visit(caseAlternative->whenExpression);
        visit(caseAlternative->thenExpression);
    }
    visit(caseExpression.getElseExpression());
}

void GroupDependencyAnalyzer::visitNodeOrRel(std::shared_ptr<Expression> expr) {
    for (auto& p : expr->constCast<NodeOrRelExpression>().getPropertyExpressions()) {
        visit(p);
    }
    switch (expr->getDataType().getLogicalTypeID()) {
    case LogicalTypeID::NODE: {
        auto& node = expr->constCast<NodeExpression>();
        visit(node.getInternalID());
    } break;
    case LogicalTypeID::REL:
    case LogicalTypeID::RECURSIVE_REL: {
        auto& rel = expr->constCast<RelExpression>();
        visit(rel.getSrcNode()->getInternalID());
        visit(rel.getDstNode()->getInternalID());
        if (rel.hasDirectionExpr()) {
            visit(rel.getDirectionExpr());
        }
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void GroupDependencyAnalyzer::visitSubquery(std::shared_ptr<binder::Expression> expr) {
    auto& subqueryExpr = expr->constCast<SubqueryExpression>();
    for (auto& node : subqueryExpr.getQueryGraphCollection()->getQueryNodes()) {
        visit(node->getInternalID());
    }
    if (subqueryExpr.hasWhereExpression()) {
        visit(subqueryExpr.getWhereExpression());
    }
}

} // namespace planner
} // namespace lbug
