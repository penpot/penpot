#include "processor/expression_mapper.h"

#include "binder/expression/case_expression.h"
#include "binder/expression/expression_util.h"
#include "binder/expression/lambda_expression.h"
#include "binder/expression/literal_expression.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/parameter_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression_visitor.h" // IWYU pragma: keep (used in assert)
#include "common/exception/not_implemented.h"
#include "common/string_format.h"
#include "expression_evaluator/case_evaluator.h"
#include "expression_evaluator/function_evaluator.h"
#include "expression_evaluator/lambda_evaluator.h"
#include "expression_evaluator/literal_evaluator.h"
#include "expression_evaluator/path_evaluator.h"
#include "expression_evaluator/pattern_evaluator.h"
#include "expression_evaluator/reference_evaluator.h"
#include "planner/operator/schema.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::evaluator;
using namespace lbug::planner;

namespace lbug {
namespace processor {

static bool canEvaluateAsFunction(ExpressionType expressionType) {
    switch (expressionType) {
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
    case ExpressionType::FUNCTION:
        return true;
    default:
        return false;
    }
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getEvaluator(
    std::shared_ptr<Expression> expression) {
    if (schema == nullptr) {
        return getConstantEvaluator(std::move(expression));
    }
    auto expressionType = expression->expressionType;
    if (schema->isExpressionInScope(*expression)) {
        return getReferenceEvaluator(std::move(expression));
    } else if (ExpressionType::LITERAL == expressionType) {
        return getLiteralEvaluator(std::move(expression));
    } else if (ExpressionUtil::isNodePattern(*expression)) {
        return getNodeEvaluator(std::move(expression));
    } else if (ExpressionUtil::isRelPattern(*expression)) {
        return getRelEvaluator(std::move(expression));
    } else if (expressionType == ExpressionType::PATH) {
        return getPathEvaluator(std::move(expression));
    } else if (expressionType == ExpressionType::PARAMETER) {
        return getParameterEvaluator(std::move(expression));
    } else if (expressionType == ExpressionType::CASE_ELSE) {
        return getCaseEvaluator(std::move(expression));
    } else if (canEvaluateAsFunction(expressionType)) {
        return getFunctionEvaluator(std::move(expression));
    } else if (parentEvaluator != nullptr) {
        return getLambdaParamEvaluator(std::move(expression));
    } else {
        // LCOV_EXCL_START
        throw NotImplementedException(stringFormat("Cannot evaluate expression with type {}.",
            ExpressionTypeUtil::toString(expressionType)));
        // LCOV_EXCL_STOP
    }
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getConstantEvaluator(
    std::shared_ptr<Expression> expression) {
    KU_ASSERT(ConstantExpressionVisitor::isConstant(*expression));
    auto expressionType = expression->expressionType;
    if (ExpressionType::LITERAL == expressionType) {
        return getLiteralEvaluator(std::move(expression));
    } else if (ExpressionType::CASE_ELSE == expressionType) {
        return getCaseEvaluator(std::move(expression));
    } else if (canEvaluateAsFunction(expressionType)) {
        return getFunctionEvaluator(std::move(expression));
    } else {
        // LCOV_EXCL_START
        throw NotImplementedException(stringFormat("Cannot evaluate expression with type {}.",
            ExpressionTypeUtil::toString(expressionType)));
        // LCOV_EXCL_STOP
    }
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getLiteralEvaluator(
    std::shared_ptr<Expression> expression) {
    auto& literalExpression = expression->constCast<LiteralExpression>();
    return std::make_unique<LiteralExpressionEvaluator>(std::move(expression),
        literalExpression.getValue());
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getParameterEvaluator(
    std::shared_ptr<Expression> expression) {
    auto& parameterExpression = expression->constCast<ParameterExpression>();
    return std::make_unique<LiteralExpressionEvaluator>(std::move(expression),
        parameterExpression.getValue());
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getReferenceEvaluator(
    std::shared_ptr<Expression> expression) const {
    KU_ASSERT(schema != nullptr);
    auto vectorPos = DataPos(schema->getExpressionPos(*expression));
    auto expressionGroup = schema->getGroup(expression->getUniqueName());
    return std::make_unique<ReferenceExpressionEvaluator>(std::move(expression),
        expressionGroup->isFlat(), vectorPos);
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getLambdaParamEvaluator(
    std::shared_ptr<Expression> expression) {
    return std::make_unique<LambdaParamEvaluator>(std::move(expression));
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getCaseEvaluator(
    std::shared_ptr<Expression> expression) {
    auto caseExpression = reinterpret_cast<CaseExpression*>(expression.get());
    std::vector<CaseAlternativeEvaluator> alternativeEvaluators;
    for (auto i = 0u; i < caseExpression->getNumCaseAlternatives(); ++i) {
        auto alternative = caseExpression->getCaseAlternative(i);
        auto whenEvaluator = getEvaluator(alternative->whenExpression);
        auto thenEvaluator = getEvaluator(alternative->thenExpression);
        alternativeEvaluators.push_back(
            CaseAlternativeEvaluator(std::move(whenEvaluator), std::move(thenEvaluator)));
    }
    auto elseEvaluator = getEvaluator(caseExpression->getElseExpression());
    return std::make_unique<CaseExpressionEvaluator>(std::move(expression),
        std::move(alternativeEvaluators), std::move(elseEvaluator));
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getFunctionEvaluator(
    std::shared_ptr<Expression> expression) {
    evaluator_vector_t childrenEvaluators;
    if (expression->getNumChildren() == 2 &&
        expression->getChild(1)->expressionType == ExpressionType::LAMBDA) {
        childrenEvaluators.push_back(getEvaluator(expression->getChild(0)));
        auto result =
            std::make_unique<ListLambdaEvaluator>(expression, std::move(childrenEvaluators));
        auto recursiveExprMapper = ExpressionMapper(schema, result.get());
        auto& lambdaExpr = expression->getChild(1)->constCast<LambdaExpression>();
        result->setLambdaRootEvaluator(
            recursiveExprMapper.getEvaluator(lambdaExpr.getFunctionExpr()));
        return result;
    }
    childrenEvaluators = getEvaluators(expression->getChildren());
    return std::make_unique<FunctionExpressionEvaluator>(std::move(expression),
        std::move(childrenEvaluators));
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getNodeEvaluator(
    std::shared_ptr<Expression> expression) {
    auto node = expression->constPtrCast<NodeExpression>();
    expression_vector children;
    children.push_back(node->getInternalID());
    children.push_back(node->getLabelExpression());
    for (auto& property : node->getPropertyExpressions()) {
        children.push_back(property);
    }
    auto childrenEvaluators = getEvaluators(children);
    return std::make_unique<PatternExpressionEvaluator>(std::move(expression),
        std::move(childrenEvaluators));
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getRelEvaluator(
    std::shared_ptr<Expression> expression) {
    auto rel = expression->constPtrCast<RelExpression>();
    expression_vector children;
    children.push_back(rel->getSrcNode()->getInternalID());
    children.push_back(rel->getDstNode()->getInternalID());
    children.push_back(rel->getLabelExpression());
    for (auto& property : rel->getPropertyExpressions()) {
        children.push_back(property);
    }
    auto childrenEvaluators = getEvaluators(children);
    if (rel->hasDirectionExpr()) {
        auto directionEvaluator = getEvaluator(rel->getDirectionExpr());
        return std::make_unique<UndirectedRelExpressionEvaluator>(std::move(expression),
            std::move(childrenEvaluators), std::move(directionEvaluator));
    }
    return std::make_unique<PatternExpressionEvaluator>(std::move(expression),
        std::move(childrenEvaluators));
}

std::unique_ptr<ExpressionEvaluator> ExpressionMapper::getPathEvaluator(
    std::shared_ptr<Expression> expression) {
    auto children = getEvaluators(expression->getChildren());
    return std::make_unique<PathExpressionEvaluator>(std::move(expression), std::move(children));
}

std::vector<std::unique_ptr<ExpressionEvaluator>> ExpressionMapper::getEvaluators(
    const expression_vector& expressions) {
    std::vector<std::unique_ptr<ExpressionEvaluator>> evaluators;
    evaluators.reserve(expressions.size());
    for (auto& expression : expressions) {
        evaluators.push_back(getEvaluator(expression));
    }
    return evaluators;
}

} // namespace processor
} // namespace lbug
