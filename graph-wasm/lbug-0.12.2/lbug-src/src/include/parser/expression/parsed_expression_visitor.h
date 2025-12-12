#pragma once

#include "parsed_expression.h"

namespace lbug {
namespace parser {

class ParsedExpressionVisitor {
public:
    virtual ~ParsedExpressionVisitor() = default;

    void visit(const ParsedExpression* expr);
    void visitUnsafe(ParsedExpression* expr);

    virtual void visitSwitch(const ParsedExpression* expr);
    virtual void visitFunctionExpr(const ParsedExpression*) {}
    virtual void visitAggFunctionExpr(const ParsedExpression*) {}
    virtual void visitPropertyExpr(const ParsedExpression*) {}
    virtual void visitLiteralExpr(const ParsedExpression*) {}
    virtual void visitVariableExpr(const ParsedExpression*) {}
    virtual void visitPathExpr(const ParsedExpression*) {}
    virtual void visitNodeRelExpr(const ParsedExpression*) {}
    virtual void visitParamExpr(const ParsedExpression*) {}
    virtual void visitSubqueryExpr(const ParsedExpression*) {}
    virtual void visitCaseExpr(const ParsedExpression*) {}
    virtual void visitGraphExpr(const ParsedExpression*) {}
    virtual void visitLambdaExpr(const ParsedExpression*) {}
    virtual void visitStar(const ParsedExpression*) {}

    void visitChildren(const ParsedExpression& expr);
    void visitCaseChildren(const ParsedExpression& expr);

    virtual void visitSwitchUnsafe(ParsedExpression*) {}
    virtual void visitChildrenUnsafe(ParsedExpression& expr);
    virtual void visitCaseChildrenUnsafe(ParsedExpression& expr);
};

class ParsedParamExprCollector : public ParsedExpressionVisitor {
public:
    std::vector<const ParsedExpression*> getParamExprs() const { return paramExprs; }
    bool hasParamExprs() const { return !paramExprs.empty(); }

    void visitParamExpr(const ParsedExpression* expr) override { paramExprs.push_back(expr); }

private:
    std::vector<const ParsedExpression*> paramExprs;
};

class ReadWriteExprAnalyzer : public ParsedExpressionVisitor {
public:
    explicit ReadWriteExprAnalyzer(main::ClientContext* context)
        : ParsedExpressionVisitor{}, context{context} {}

    bool isReadOnly() const { return readOnly; }
    void visitFunctionExpr(const ParsedExpression* expr) override;

private:
    main::ClientContext* context;
    bool readOnly = true;
};

class MacroParameterReplacer : public ParsedExpressionVisitor {
public:
    explicit MacroParameterReplacer(
        const std::unordered_map<std::string, ParsedExpression*>& nameToExpr)
        : nameToExpr{nameToExpr} {}

    std::unique_ptr<ParsedExpression> replace(std::unique_ptr<ParsedExpression> input);

private:
    void visitSwitchUnsafe(ParsedExpression* expr) override;

    std::unique_ptr<ParsedExpression> getReplace(const std::string& name);

private:
    const std::unordered_map<std::string, ParsedExpression*>& nameToExpr;
};

} // namespace parser
} // namespace lbug
