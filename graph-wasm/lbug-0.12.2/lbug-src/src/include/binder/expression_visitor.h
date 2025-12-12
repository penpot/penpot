#pragma once

#include "binder/expression/expression.h"

namespace lbug {
namespace binder {

class ExpressionChildrenCollector {
public:
    static expression_vector collectChildren(const Expression& expression);

private:
    static expression_vector collectCaseChildren(const Expression& expression);

    static expression_vector collectSubqueryChildren(const Expression& expression);

    static expression_vector collectNodeChildren(const Expression& expression);

    static expression_vector collectRelChildren(const Expression& expression);
};

class ExpressionVisitor {
public:
    virtual ~ExpressionVisitor() = default;

    void visit(std::shared_ptr<Expression> expr);

    static bool isRandom(const Expression& expression);

protected:
    void visitSwitch(std::shared_ptr<Expression> expr);
    virtual void visitFunctionExpr(std::shared_ptr<Expression>) {}
    virtual void visitAggFunctionExpr(std::shared_ptr<Expression>) {}
    virtual void visitPropertyExpr(std::shared_ptr<Expression>) {}
    virtual void visitLiteralExpr(std::shared_ptr<Expression>) {}
    virtual void visitVariableExpr(std::shared_ptr<Expression>) {}
    virtual void visitPathExpr(std::shared_ptr<Expression>) {}
    virtual void visitNodeRelExpr(std::shared_ptr<Expression>) {}
    virtual void visitParamExpr(std::shared_ptr<Expression>) {}
    virtual void visitSubqueryExpr(std::shared_ptr<Expression>) {}
    virtual void visitCaseExpr(std::shared_ptr<Expression>) {}
    virtual void visitGraphExpr(std::shared_ptr<Expression>) {}
    virtual void visitLambdaExpr(std::shared_ptr<Expression>) {}

    virtual void visitChildren(const Expression& expr);
    void visitCaseExprChildren(const Expression& expr);
};

// Do not collect subquery expression recursively. Caller should handle recursive subquery instead.
class SubqueryExprCollector final : public ExpressionVisitor {
public:
    bool hasSubquery() const { return !exprs.empty(); }
    expression_vector getSubqueryExprs() const { return exprs; }

protected:
    void visitSubqueryExpr(std::shared_ptr<Expression> expr) override { exprs.push_back(expr); }

private:
    expression_vector exprs;
};

class DependentVarNameCollector final : public ExpressionVisitor {
public:
    std::unordered_set<std::string> getVarNames() const { return varNames; }

protected:
    void visitSubqueryExpr(std::shared_ptr<Expression> expr) override;
    void visitPropertyExpr(std::shared_ptr<Expression> expr) override;
    void visitNodeRelExpr(std::shared_ptr<Expression> expr) override;
    void visitVariableExpr(std::shared_ptr<Expression> expr) override;

private:
    std::unordered_set<std::string> varNames;
};

class PropertyExprCollector final : public ExpressionVisitor {
public:
    expression_vector getPropertyExprs() const { return expressions; }

protected:
    void visitSubqueryExpr(std::shared_ptr<Expression> expr) override;
    void visitPropertyExpr(std::shared_ptr<Expression> expr) override;
    void visitNodeRelExpr(std::shared_ptr<Expression> expr) override;

private:
    expression_vector expressions;
};

class ConstantExpressionVisitor {
public:
    static bool needFold(const Expression& expr);
    static bool isConstant(const Expression& expr);

private:
    static bool visitFunction(const Expression& expr);
    static bool visitCase(const Expression& expr);
    static bool visitChildren(const Expression& expr);
};

} // namespace binder
} // namespace lbug
