#include "expression_evaluator/expression_evaluator_visitor.h"

#include "expression_evaluator/case_evaluator.h"

namespace lbug {
namespace evaluator {

void ExpressionEvaluatorVisitor::visitSwitch(ExpressionEvaluator* evaluator) {
    switch (evaluator->getEvaluatorType()) {
    case EvaluatorType::CASE_ELSE: {
        visitCase(evaluator);
    } break;
    case EvaluatorType::FUNCTION: {
        visitFunction(evaluator);
    } break;
    case EvaluatorType::LAMBDA_PARAM: {
        visitLambdaParam(evaluator);
    } break;
    case EvaluatorType::LIST_LAMBDA: {
        visitListLambda(evaluator);
    } break;
    case EvaluatorType::LITERAL: {
        visitLiteral(evaluator);
    } break;
    case EvaluatorType::PATH: {
        visitPath(evaluator);
    } break;
    case EvaluatorType::NODE_REL: {
        visitPattern(evaluator);
    } break;
    case EvaluatorType::REFERENCE: {
        visitReference(evaluator);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void LambdaParamEvaluatorCollector::visit(ExpressionEvaluator* evaluator) {
    std::vector<ExpressionEvaluator*> children;
    switch (evaluator->getEvaluatorType()) {
    case EvaluatorType::CASE_ELSE: {
        auto& caseEvaluator = evaluator->constCast<CaseExpressionEvaluator>();
        children.push_back(caseEvaluator.getElseEvaluator());
        for (auto& alternativeEvaluator : caseEvaluator.getAlternativeEvaluators()) {
            children.push_back(alternativeEvaluator.whenEvaluator.get());
            children.push_back(alternativeEvaluator.thenEvaluator.get());
        }
    } break;
    case EvaluatorType::LAMBDA_PARAM: {
        evaluators.push_back(evaluator);
        return;
    }
    default: {
        for (auto& child : evaluator->getChildren()) {
            children.push_back(child.get());
        }
    }
    }
    for (auto& child : children) {
        visit(child);
        visitSwitch(child);
    }
}

} // namespace evaluator
} // namespace lbug
