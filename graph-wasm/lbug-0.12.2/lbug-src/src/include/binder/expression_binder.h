#pragma once

#include "binder/expression/expression.h"
#include "common/types/value/value.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace main {
class ClientContext;
}

namespace function {
struct Function;
}

namespace binder {

class Binder;
struct CaseAlternative;

struct ExpressionBinderConfig {
    // If a property is not in projection list but required in order by after aggregation,
    // we need to bind it as struct extraction because node/rel must have been evaluated as
    // struct during aggregate
    // e.g. RETURN a, COUNT(*) ORDER BY a.ID
    bool bindOrderByAfterAggregate = false;
    // If a node is single labeled, we rewrite its label function as string literal. This however,
    // should be applied to recursive pattern predicate because if path is of length <= 1, there
    // is no intermediate node and thus the predicate should be a noop. If we try to evaluate, it
    // may lead to empty result.
    // e.g. [* (r, n | WHERE label(n)='dummy') ]
    bool disableLabelFunctionLiteralRewrite = false;
};

class ExpressionBinder {
    friend class Binder;

public:
    ExpressionBinder(Binder* queryBinder, main::ClientContext* context)
        : binder{queryBinder}, context{context} {}

    std::shared_ptr<Expression> bindExpression(const parser::ParsedExpression& parsedExpression);

    // TODO(Xiyang): move to an expression rewriter
    LBUG_API std::shared_ptr<Expression> foldExpression(
        const std::shared_ptr<Expression>& expression) const;

    // Boolean expressions.
    std::shared_ptr<Expression> bindBooleanExpression(
        const parser::ParsedExpression& parsedExpression);
    std::shared_ptr<Expression> bindBooleanExpression(common::ExpressionType expressionType,
        const expression_vector& children);

    std::shared_ptr<Expression> combineBooleanExpressions(common::ExpressionType expressionType,
        std::shared_ptr<Expression> left, std::shared_ptr<Expression> right);
    // Comparison expressions.
    std::shared_ptr<Expression> bindComparisonExpression(
        const parser::ParsedExpression& parsedExpression);
    std::shared_ptr<Expression> bindComparisonExpression(common::ExpressionType expressionType,
        const expression_vector& children);
    std::shared_ptr<Expression> createEqualityComparisonExpression(std::shared_ptr<Expression> left,
        std::shared_ptr<Expression> right);
    // Null operator expressions.
    std::shared_ptr<Expression> bindNullOperatorExpression(
        const parser::ParsedExpression& parsedExpression);
    std::shared_ptr<Expression> bindNullOperatorExpression(common::ExpressionType expressionType,
        const expression_vector& children);

    // Property expressions.
    expression_vector bindPropertyStarExpression(const parser::ParsedExpression& parsedExpression);
    static expression_vector bindNodeOrRelPropertyStarExpression(const Expression& child);
    expression_vector bindStructPropertyStarExpression(const std::shared_ptr<Expression>& child);
    std::shared_ptr<Expression> bindPropertyExpression(
        const parser::ParsedExpression& parsedExpression);
    static std::shared_ptr<Expression> bindNodeOrRelPropertyExpression(const Expression& child,
        const std::string& propertyName);
    std::shared_ptr<Expression> bindStructPropertyExpression(std::shared_ptr<Expression> child,
        const std::string& propertyName);
    // Function expressions.
    std::shared_ptr<Expression> bindFunctionExpression(const parser::ParsedExpression& expr);
    void bindLambdaExpression(const Expression& lambdaInput, Expression& lambdaExpr) const;
    std::shared_ptr<Expression> bindLambdaExpression(
        const parser::ParsedExpression& parsedExpr) const;

    std::shared_ptr<Expression> bindScalarFunctionExpression(
        const parser::ParsedExpression& parsedExpression, const std::string& functionName);
    std::shared_ptr<Expression> bindScalarFunctionExpression(const expression_vector& children,
        const std::string& functionName,
        std::vector<std::string> optionalArguments = std::vector<std::string>{});
    std::shared_ptr<Expression> bindRewriteFunctionExpression(const parser::ParsedExpression& expr);
    std::shared_ptr<Expression> bindAggregateFunctionExpression(
        const parser::ParsedExpression& parsedExpression, const std::string& functionName,
        bool isDistinct);
    std::shared_ptr<Expression> bindMacroExpression(
        const parser::ParsedExpression& parsedExpression, const std::string& macroName);

    // Parameter expressions.
    std::shared_ptr<Expression> bindParameterExpression(
        const parser::ParsedExpression& parsedExpression);
    // Literal expressions.
    std::shared_ptr<Expression> bindLiteralExpression(
        const parser::ParsedExpression& parsedExpression) const;
    std::shared_ptr<Expression> createLiteralExpression(const common::Value& value) const;
    std::shared_ptr<Expression> createLiteralExpression(const std::string& strVal) const;
    std::shared_ptr<Expression> createNullLiteralExpression() const;
    std::shared_ptr<Expression> createNullLiteralExpression(const common::Value& value) const;
    // Variable expressions.
    std::shared_ptr<Expression> bindVariableExpression(
        const parser::ParsedExpression& parsedExpression) const;
    std::shared_ptr<Expression> bindVariableExpression(const std::string& varName) const;
    std::shared_ptr<Expression> createVariableExpression(common::LogicalType logicalType,
        std::string_view name) const;
    std::shared_ptr<Expression> createVariableExpression(common::LogicalType logicalType,
        std::string name) const;
    // Subquery expressions.
    std::shared_ptr<Expression> bindSubqueryExpression(const parser::ParsedExpression& parsedExpr);
    // Case expressions.
    std::shared_ptr<Expression> bindCaseExpression(
        const parser::ParsedExpression& parsedExpression);

    /****** cast *****/
    LBUG_API std::shared_ptr<Expression> implicitCastIfNecessary(
        const std::shared_ptr<Expression>& expression, const common::LogicalType& targetType);
    // Use implicitCast to cast to types you have obtained through known implicit casting rules.
    // Use forceCast to cast to types you have obtained through other means, for example,
    // through a maxLogicalType function
    std::shared_ptr<Expression> implicitCast(const std::shared_ptr<Expression>& expression,
        const common::LogicalType& targetType);
    std::shared_ptr<Expression> forceCast(const std::shared_ptr<Expression>& expression,
        const common::LogicalType& targetType);

    // Parameter
    void addParameter(const std::string& name, std::shared_ptr<common::Value> value);
    const std::unordered_set<std::string>& getUnknownParameters() const {
        return unknownParameters;
    }
    const std::unordered_map<std::string, std::shared_ptr<common::Value>>&
    getKnownParameters() const {
        return knownParameters;
    }

    std::string getUniqueName(const std::string& name) const;

    const ExpressionBinderConfig& getConfig() { return config; }

private:
    Binder* binder;
    main::ClientContext* context;
    std::unordered_set<std::string> unknownParameters;
    std::unordered_map<std::string, std::shared_ptr<common::Value>> knownParameters;
    ExpressionBinderConfig config;
};

} // namespace binder
} // namespace lbug
