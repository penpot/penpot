#include "binder/expression_visitor.h"

#include "binder/expression/case_expression.h"
#include "binder/expression/lambda_expression.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/property_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression/subquery_expression.h"
#include "common/exception/not_implemented.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/sequence/sequence_functions.h"
#include "function/uuid/vector_uuid_functions.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

void ExpressionVisitor::visit(std::shared_ptr<Expression> expr) {
    visitChildren(*expr);
    visitSwitch(expr);
}

void ExpressionVisitor::visitSwitch(std::shared_ptr<Expression> expr) {
    switch (expr->expressionType) {
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
    case ExpressionType::IS_NOT_NULL:
    case ExpressionType::FUNCTION: {
        visitFunctionExpr(expr);
    } break;
    case ExpressionType::AGGREGATE_FUNCTION: {
        visitAggFunctionExpr(expr);
    } break;
    case ExpressionType::PROPERTY: {
        visitPropertyExpr(expr);
    } break;
    case ExpressionType::LITERAL: {
        visitLiteralExpr(expr);
    } break;
    case ExpressionType::VARIABLE: {
        visitVariableExpr(expr);
    } break;
    case ExpressionType::PATH: {
        visitPathExpr(expr);
    } break;
    case ExpressionType::PATTERN: {
        visitNodeRelExpr(expr);
    } break;
    case ExpressionType::PARAMETER: {
        visitParamExpr(expr);
    } break;
    case ExpressionType::SUBQUERY: {
        visitSubqueryExpr(expr);
    } break;
    case ExpressionType::CASE_ELSE: {
        visitCaseExpr(expr);
    } break;
    case ExpressionType::GRAPH: {
        visitGraphExpr(expr);
    } break;
    case ExpressionType::LAMBDA: {
        visitLambdaExpr(expr);
    } break;
        // LCOV_EXCL_START
    default:
        throw NotImplementedException("ExpressionVisitor::visitSwitch");
        // LCOV_EXCL_STOP
    }
}

void ExpressionVisitor::visitChildren(const Expression& expr) {
    switch (expr.expressionType) {
    case ExpressionType::CASE_ELSE: {
        visitCaseExprChildren(expr);
    } break;
    case ExpressionType::LAMBDA: {
        auto& lambda = expr.constCast<LambdaExpression>();
        visit(lambda.getFunctionExpr());
    } break;
    default: {
        for (auto& child : expr.getChildren()) {
            visit(child);
        }
    }
    }
}

void ExpressionVisitor::visitCaseExprChildren(const Expression& expr) {
    auto& caseExpr = expr.constCast<CaseExpression>();
    for (auto i = 0u; i < caseExpr.getNumCaseAlternatives(); ++i) {
        auto caseAlternative = caseExpr.getCaseAlternative(i);
        visit(caseAlternative->whenExpression);
        visit(caseAlternative->thenExpression);
    }
    visit(caseExpr.getElseExpression());
}

expression_vector ExpressionChildrenCollector::collectChildren(const Expression& expression) {
    switch (expression.expressionType) {
    case ExpressionType::CASE_ELSE: {
        return collectCaseChildren(expression);
    }
    case ExpressionType::SUBQUERY: {
        return collectSubqueryChildren(expression);
    }
    case ExpressionType::PATTERN: {
        switch (expression.dataType.getLogicalTypeID()) {
        case LogicalTypeID::NODE: {
            return collectNodeChildren(expression);
        }
        case LogicalTypeID::REL: {
            return collectRelChildren(expression);
        }
        default: {
            return expression_vector{};
        }
        }
    }
    default: {
        return expression.children;
    }
    }
}

expression_vector ExpressionChildrenCollector::collectCaseChildren(const Expression& expression) {
    expression_vector result;
    auto& caseExpression = expression.constCast<CaseExpression>();
    for (auto i = 0u; i < caseExpression.getNumCaseAlternatives(); ++i) {
        auto caseAlternative = caseExpression.getCaseAlternative(i);
        result.push_back(caseAlternative->whenExpression);
        result.push_back(caseAlternative->thenExpression);
    }
    result.push_back(caseExpression.getElseExpression());
    return result;
}

expression_vector ExpressionChildrenCollector::collectSubqueryChildren(
    const Expression& expression) {
    expression_vector result;
    auto& subqueryExpression = expression.constCast<SubqueryExpression>();
    for (auto& node : subqueryExpression.getQueryGraphCollection()->getQueryNodes()) {
        result.push_back(node->getInternalID());
    }
    if (subqueryExpression.hasWhereExpression()) {
        result.push_back(subqueryExpression.getWhereExpression());
    }
    return result;
}

expression_vector ExpressionChildrenCollector::collectNodeChildren(const Expression& expression) {
    expression_vector result;
    auto& node = expression.constCast<NodeExpression>();
    for (auto& property : node.getPropertyExpressions()) {
        result.push_back(property);
    }
    result.push_back(node.getInternalID());
    return result;
}

expression_vector ExpressionChildrenCollector::collectRelChildren(const Expression& expression) {
    expression_vector result;
    auto& rel = expression.constCast<RelExpression>();
    result.push_back(rel.getSrcNode()->getInternalID());
    result.push_back(rel.getDstNode()->getInternalID());
    for (auto& property : rel.getPropertyExpressions()) {
        result.push_back(property);
    }
    if (rel.hasDirectionExpr()) {
        result.push_back(rel.getDirectionExpr());
    }
    return result;
}

bool ExpressionVisitor::isRandom(const Expression& expression) {
    if (expression.expressionType != ExpressionType::FUNCTION) {
        return false;
    }
    auto& funcExpr = expression.constCast<ScalarFunctionExpression>();
    auto funcName = funcExpr.getFunction().name;
    if (funcName == function::GenRandomUUIDFunction::name ||
        funcName == function::RandFunction::name) {
        return true;
    }
    for (auto& child : ExpressionChildrenCollector::collectChildren(expression)) {
        if (isRandom(*child)) {
            return true;
        }
    }
    return false;
}

void DependentVarNameCollector::visitSubqueryExpr(std::shared_ptr<Expression> expr) {
    auto& subqueryExpr = expr->constCast<SubqueryExpression>();
    for (auto& node : subqueryExpr.getQueryGraphCollection()->getQueryNodes()) {
        varNames.insert(node->getUniqueName());
    }
    if (subqueryExpr.hasWhereExpression()) {
        visit(subqueryExpr.getWhereExpression());
    }
}

void DependentVarNameCollector::visitPropertyExpr(std::shared_ptr<Expression> expr) {
    varNames.insert(expr->constCast<PropertyExpression>().getVariableName());
}

void DependentVarNameCollector::visitNodeRelExpr(std::shared_ptr<Expression> expr) {
    varNames.insert(expr->getUniqueName());
    if (expr->getDataType().getLogicalTypeID() == LogicalTypeID::REL) {
        auto& rel = expr->constCast<RelExpression>();
        varNames.insert(rel.getSrcNodeName());
        varNames.insert(rel.getDstNodeName());
    }
}

void DependentVarNameCollector::visitVariableExpr(std::shared_ptr<Expression> expr) {
    varNames.insert(expr->getUniqueName());
}

void PropertyExprCollector::visitSubqueryExpr(std::shared_ptr<Expression> expr) {
    auto& subqueryExpr = expr->constCast<SubqueryExpression>();
    for (auto& rel : subqueryExpr.getQueryGraphCollection()->getQueryRels()) {
        if (rel->isEmpty() || rel->getRelType() != QueryRelType::NON_RECURSIVE) {
            // If a query rel is empty then it does not have an internal id property.
            continue;
        }
        expressions.push_back(rel->getInternalID());
    }
    if (subqueryExpr.hasWhereExpression()) {
        visit(subqueryExpr.getWhereExpression());
    }
}

void PropertyExprCollector::visitPropertyExpr(std::shared_ptr<Expression> expr) {
    expressions.push_back(expr);
}

void PropertyExprCollector::visitNodeRelExpr(std::shared_ptr<Expression> expr) {
    for (auto& property : expr->constCast<NodeOrRelExpression>().getPropertyExpressions()) {
        expressions.push_back(property);
    }
}

bool ConstantExpressionVisitor::needFold(const Expression& expr) {
    if (expr.expressionType == common::ExpressionType::LITERAL) {
        return false; // No need to fold a literal.
    }
    return isConstant(expr);
}

bool ConstantExpressionVisitor::isConstant(const Expression& expr) {
    switch (expr.expressionType) {
    case ExpressionType::LITERAL:
        return true;
    case ExpressionType::AGGREGATE_FUNCTION:
    case ExpressionType::PROPERTY:
    case ExpressionType::VARIABLE:
    case ExpressionType::PATH:
    case ExpressionType::PATTERN:
    case ExpressionType::PARAMETER:
    case ExpressionType::SUBQUERY:
    case ExpressionType::GRAPH:
    case ExpressionType::LAMBDA:
        return false;
    case ExpressionType::FUNCTION:
        return visitFunction(expr);
    case ExpressionType::CASE_ELSE:
        return visitCase(expr);
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
    case ExpressionType::IS_NOT_NULL:
        return visitChildren(expr);
        // LCOV_EXCL_START
    default:
        throw NotImplementedException("ConstantExpressionVisitor::isConstant");
        // LCOV_EXCL_STOP
    }
}

bool ConstantExpressionVisitor::visitFunction(const Expression& expr) {
    auto& funcExpr = expr.constCast<ScalarFunctionExpression>();
    if (funcExpr.getFunction().name == function::NextValFunction::name) {
        return false;
    }
    if (funcExpr.getFunction().name == function::GenRandomUUIDFunction::name) {
        return false;
    }
    if (funcExpr.getFunction().name == function::RandFunction::name) {
        return false;
    }
    return visitChildren(expr);
}

bool ConstantExpressionVisitor::visitCase(const Expression& expr) {
    auto& caseExpr = expr.constCast<CaseExpression>();
    for (auto i = 0u; i < caseExpr.getNumCaseAlternatives(); ++i) {
        auto caseAlternative = caseExpr.getCaseAlternative(i);
        if (!isConstant(*caseAlternative->whenExpression)) {
            return false;
        }
        if (!isConstant(*caseAlternative->thenExpression)) {
            return false;
        }
    }
    return isConstant(*caseExpr.getElseExpression());
}

bool ConstantExpressionVisitor::visitChildren(const Expression& expr) {
    for (auto& child : expr.getChildren()) {
        if (!isConstant(*child)) {
            return false;
        }
    }
    return true;
}

} // namespace binder
} // namespace lbug
