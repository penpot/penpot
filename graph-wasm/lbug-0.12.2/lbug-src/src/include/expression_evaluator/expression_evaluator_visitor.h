#pragma once

#include "expression_evaluator.h"

namespace lbug {
namespace evaluator {

class ExpressionEvaluatorVisitor {
public:
    virtual ~ExpressionEvaluatorVisitor() = default;

protected:
    void visitSwitch(ExpressionEvaluator* evaluator);

    virtual void visitCase(ExpressionEvaluator*) {}
    virtual void visitFunction(ExpressionEvaluator*) {}
    virtual void visitLambdaParam(ExpressionEvaluator*) {}
    virtual void visitListLambda(ExpressionEvaluator*) {}
    virtual void visitLiteral(ExpressionEvaluator*) {}
    virtual void visitPath(ExpressionEvaluator*) {}
    virtual void visitReference(ExpressionEvaluator*) {}
    // NOTE: If one decides to overwrite pattern evaluator visitor, make sure we differentiate
    // pattern evaluator and undirected rel evaluator.
    void visitPattern(ExpressionEvaluator*) {}
};

class LambdaParamEvaluatorCollector final : public ExpressionEvaluatorVisitor {
public:
    void visit(ExpressionEvaluator* evaluator);

    std::vector<ExpressionEvaluator*> getEvaluators() const { return evaluators; }

protected:
    void visitLambdaParam(ExpressionEvaluator* evaluator) override {
        evaluators.push_back(evaluator);
    }

private:
    std::vector<ExpressionEvaluator*> evaluators;
};

} // namespace evaluator
} // namespace lbug
