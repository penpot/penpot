#include "function/aggregate/count_star.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/cast/functions/cast_from_string_functions.h"
#include "function/list/vector_list_functions.h"
#include "function/string/vector_string_functions.h"
#include "function/struct/vector_struct_functions.h"
#include "parser/expression/parsed_case_expression.h"
#include "parser/expression/parsed_function_expression.h"
#include "parser/expression/parsed_lambda_expression.h"
#include "parser/expression/parsed_literal_expression.h"
#include "parser/expression/parsed_parameter_expression.h"
#include "parser/expression/parsed_property_expression.h"
#include "parser/expression/parsed_subquery_expression.h"
#include "parser/expression/parsed_variable_expression.h"
#include "parser/transformer.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace parser {

std::unique_ptr<ParsedExpression> Transformer::transformExpression(
    CypherParser::OC_ExpressionContext& ctx) {
    return transformOrExpression(*ctx.oC_OrExpression());
}

std::unique_ptr<ParsedExpression> Transformer::transformOrExpression(
    CypherParser::OC_OrExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto& xorExpression : ctx.oC_XorExpression()) {
        auto next = transformXorExpression(*xorExpression);
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " OR " + next->getRawName();
            expression = std::make_unique<ParsedExpression>(ExpressionType::OR,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformXorExpression(
    CypherParser::OC_XorExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto& andExpression : ctx.oC_AndExpression()) {
        auto next = transformAndExpression(*andExpression);
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " XOR " + next->getRawName();
            expression = std::make_unique<ParsedExpression>(ExpressionType::XOR,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformAndExpression(
    CypherParser::OC_AndExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto& notExpression : ctx.oC_NotExpression()) {
        auto next = transformNotExpression(*notExpression);
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " AND " + next->getRawName();
            expression = std::make_unique<ParsedExpression>(ExpressionType::AND,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformNotExpression(
    CypherParser::OC_NotExpressionContext& ctx) {
    auto result = transformComparisonExpression(*ctx.oC_ComparisonExpression());
    if (!ctx.NOT().empty()) {
        for ([[maybe_unused]] auto& _ : ctx.NOT()) {
            auto rawName = "NOT " + result->toString();
            result = std::make_unique<ParsedExpression>(ExpressionType::NOT, std::move(result),
                std::move(rawName));
        }
    }
    return result;
}

std::unique_ptr<ParsedExpression> Transformer::transformComparisonExpression(
    CypherParser::OC_ComparisonExpressionContext& ctx) {
    if (1 == ctx.kU_BitwiseOrOperatorExpression().size()) {
        return transformBitwiseOrOperatorExpression(*ctx.kU_BitwiseOrOperatorExpression(0));
    }
    // Antlr parser throws error for conjunctive comparison.
    // Transformer should only handle the case of single comparison operator.
    KU_ASSERT(ctx.kU_ComparisonOperator().size() == 1);
    auto left = transformBitwiseOrOperatorExpression(*ctx.kU_BitwiseOrOperatorExpression(0));
    auto right = transformBitwiseOrOperatorExpression(*ctx.kU_BitwiseOrOperatorExpression(1));
    auto comparisonOperator = ctx.kU_ComparisonOperator()[0]->getText();
    if (comparisonOperator == "=") {
        return std::make_unique<ParsedExpression>(ExpressionType::EQUALS, std::move(left),
            std::move(right), ctx.getText());
    } else if (comparisonOperator == "<>") {
        return std::make_unique<ParsedExpression>(ExpressionType::NOT_EQUALS, std::move(left),
            std::move(right), ctx.getText());
    } else if (comparisonOperator == ">") {
        return std::make_unique<ParsedExpression>(ExpressionType::GREATER_THAN, std::move(left),
            std::move(right), ctx.getText());
    } else if (comparisonOperator == ">=") {
        return std::make_unique<ParsedExpression>(ExpressionType::GREATER_THAN_EQUALS,
            std::move(left), std::move(right), ctx.getText());
    } else if (comparisonOperator == "<") {
        return std::make_unique<ParsedExpression>(ExpressionType::LESS_THAN, std::move(left),
            std::move(right), ctx.getText());
    } else {
        KU_ASSERT(comparisonOperator == "<=");
        return std::make_unique<ParsedExpression>(ExpressionType::LESS_THAN_EQUALS, std::move(left),
            std::move(right), ctx.getText());
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformBitwiseOrOperatorExpression(
    CypherParser::KU_BitwiseOrOperatorExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto i = 0ul; i < ctx.kU_BitwiseAndOperatorExpression().size(); ++i) {
        auto next = transformBitwiseAndOperatorExpression(*ctx.kU_BitwiseAndOperatorExpression(i));
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " | " + next->getRawName();
            expression = std::make_unique<ParsedFunctionExpression>(BitwiseOrFunction::name,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformBitwiseAndOperatorExpression(
    CypherParser::KU_BitwiseAndOperatorExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto i = 0ul; i < ctx.kU_BitShiftOperatorExpression().size(); ++i) {
        auto next = transformBitShiftOperatorExpression(*ctx.kU_BitShiftOperatorExpression(i));
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " & " + next->getRawName();
            expression = std::make_unique<ParsedFunctionExpression>(BitwiseAndFunction::name,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformBitShiftOperatorExpression(
    CypherParser::KU_BitShiftOperatorExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto i = 0ul; i < ctx.oC_AddOrSubtractExpression().size(); ++i) {
        auto next = transformAddOrSubtractExpression(*ctx.oC_AddOrSubtractExpression(i));
        if (!expression) {
            expression = std::move(next);
        } else {
            auto bitShiftOperator = ctx.kU_BitShiftOperator(i - 1)->getText();
            auto rawName =
                expression->getRawName() + " " + bitShiftOperator + " " + next->getRawName();
            if (bitShiftOperator == "<<") {
                expression = std::make_unique<ParsedFunctionExpression>(BitShiftLeftFunction::name,
                    std::move(expression), std::move(next), rawName);
            } else {
                KU_ASSERT(bitShiftOperator == ">>");
                expression = std::make_unique<ParsedFunctionExpression>(BitShiftRightFunction::name,
                    std::move(expression), std::move(next), rawName);
            }
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformAddOrSubtractExpression(
    CypherParser::OC_AddOrSubtractExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto i = 0ul; i < ctx.oC_MultiplyDivideModuloExpression().size(); ++i) {
        auto next =
            transformMultiplyDivideModuloExpression(*ctx.oC_MultiplyDivideModuloExpression(i));
        if (!expression) {
            expression = std::move(next);
        } else {
            auto arithmeticOperator = ctx.kU_AddOrSubtractOperator(i - 1)->getText();
            auto rawName =
                expression->getRawName() + " " + arithmeticOperator + " " + next->getRawName();
            expression = std::make_unique<ParsedFunctionExpression>(arithmeticOperator,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformMultiplyDivideModuloExpression(
    CypherParser::OC_MultiplyDivideModuloExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto i = 0ul; i < ctx.oC_PowerOfExpression().size(); i++) {
        auto next = transformPowerOfExpression(*ctx.oC_PowerOfExpression(i));
        if (!expression) {
            expression = std::move(next);
        } else {
            auto arithmeticOperator = ctx.kU_MultiplyDivideModuloOperator(i - 1)->getText();
            auto rawName =
                expression->getRawName() + " " + arithmeticOperator + " " + next->getRawName();
            expression = std::make_unique<ParsedFunctionExpression>(arithmeticOperator,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformPowerOfExpression(
    CypherParser::OC_PowerOfExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> expression;
    for (auto& stringListNullOperatorExpression : ctx.oC_StringListNullOperatorExpression()) {
        auto next = transformStringListNullOperatorExpression(*stringListNullOperatorExpression);
        if (!expression) {
            expression = std::move(next);
        } else {
            auto rawName = expression->getRawName() + " ^ " + next->getRawName();
            expression = std::make_unique<ParsedFunctionExpression>(PowerFunction::name,
                std::move(expression), std::move(next), rawName);
        }
    }
    return expression;
}

std::unique_ptr<ParsedExpression> Transformer::transformUnaryAddSubtractOrFactorialExpression(
    CypherParser::OC_UnaryAddSubtractOrFactorialExpressionContext& ctx) {
    auto atomCtx = ctx.oC_PropertyOrLabelsExpression()->oC_Atom();
    bool isNumberLiteral = atomCtx->oC_Literal() && atomCtx->oC_Literal()->oC_NumberLiteral();
    std::unique_ptr<ParsedExpression> result;
    if (isNumberLiteral) {
        // Try parse -number as a signed literal. This is to avoid
        // -170141183460469231731687303715884105728 being parsed as
        // 170141183460469231731687303715884105728 and cause overflow
        result = transformNumberLiteral(*atomCtx->oC_Literal()->oC_NumberLiteral(),
            ctx.MINUS().size() % 2 == 1);
    } else {
        result = transformPropertyOrLabelsExpression(*ctx.oC_PropertyOrLabelsExpression());
    }
    if (ctx.FACTORIAL()) { // Factorial has a higher precedence
        auto raw = result->toString() + "!";
        result = std::make_unique<ParsedFunctionExpression>(FactorialFunction::name,
            std::move(result), std::move(raw));
    }
    if (!ctx.MINUS().empty() && !isNumberLiteral) {
        for ([[maybe_unused]] auto& _ : ctx.MINUS()) {
            auto raw = "-" + result->toString();
            result = std::make_unique<ParsedFunctionExpression>(NegateFunction::name,
                std::move(result), std::move(raw));
        }
    }
    return result;
}

std::unique_ptr<ParsedExpression> Transformer::transformStringListNullOperatorExpression(
    CypherParser::OC_StringListNullOperatorExpressionContext& ctx) {
    auto unaryAddSubtractOrFactorialExpression = transformUnaryAddSubtractOrFactorialExpression(
        *ctx.oC_UnaryAddSubtractOrFactorialExpression());
    if (ctx.oC_NullOperatorExpression()) {
        return transformNullOperatorExpression(*ctx.oC_NullOperatorExpression(),
            std::move(unaryAddSubtractOrFactorialExpression));
    }
    if (!ctx.oC_ListOperatorExpression().empty()) {
        auto result = transformListOperatorExpression(*ctx.oC_ListOperatorExpression(0),
            std::move(unaryAddSubtractOrFactorialExpression));
        for (auto i = 1u; i < ctx.oC_ListOperatorExpression().size(); ++i) {
            result = transformListOperatorExpression(*ctx.oC_ListOperatorExpression(i),
                std::move(result));
        }
        return result;
    }
    if (ctx.oC_StringOperatorExpression()) {
        return transformStringOperatorExpression(*ctx.oC_StringOperatorExpression(),
            std::move(unaryAddSubtractOrFactorialExpression));
    }
    return unaryAddSubtractOrFactorialExpression;
}

std::unique_ptr<ParsedExpression> Transformer::transformStringOperatorExpression(
    CypherParser::OC_StringOperatorExpressionContext& ctx,
    std::unique_ptr<ParsedExpression> propertyExpression) {
    auto rawExpression = propertyExpression->getRawName() + " " + ctx.getText();
    auto right = transformPropertyOrLabelsExpression(*ctx.oC_PropertyOrLabelsExpression());
    if (ctx.STARTS()) {
        return std::make_unique<ParsedFunctionExpression>(StartsWithFunction::name,
            std::move(propertyExpression), std::move(right), rawExpression);
    } else if (ctx.ENDS()) {
        return std::make_unique<ParsedFunctionExpression>(EndsWithFunction::name,
            std::move(propertyExpression), std::move(right), rawExpression);
    } else if (ctx.CONTAINS()) {
        return std::make_unique<ParsedFunctionExpression>(ContainsFunction::name,
            std::move(propertyExpression), std::move(right), rawExpression);
    } else {
        KU_ASSERT(ctx.oC_RegularExpression());
        return std::make_unique<ParsedFunctionExpression>(RegexpFullMatchFunction::name,
            std::move(propertyExpression), std::move(right), rawExpression);
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformListOperatorExpression(
    CypherParser::OC_ListOperatorExpressionContext& ctx, std::unique_ptr<ParsedExpression> child) {
    auto raw = child->getRawName() + ctx.getText();
    if (ctx.IN()) { // x IN y
        auto listContains =
            std::make_unique<ParsedFunctionExpression>(ListContainsFunction::name, std::move(raw));
        auto right = transformPropertyOrLabelsExpression(*ctx.oC_PropertyOrLabelsExpression());
        listContains->addChild(std::move(right));
        listContains->addChild(std::move(child));
        return listContains;
    }
    if (ctx.COLON() || ctx.DOTDOT()) { // x[:]/x[..]
        auto listSlice =
            std::make_unique<ParsedFunctionExpression>(ListSliceFunction::name, std::move(raw));
        listSlice->addChild(std::move(child));
        std::unique_ptr<ParsedExpression> left;
        std::unique_ptr<ParsedExpression> right;
        if (ctx.oC_Expression().size() == 2) { // [left:right]/[left..right]
            left = transformExpression(*ctx.oC_Expression(0));
            right = transformExpression(*ctx.oC_Expression(1));
        } else if (ctx.oC_Expression().size() == 0) { // [:]/[..]
            left = std::make_unique<ParsedLiteralExpression>(Value(1), "1");
            right = std::make_unique<ParsedLiteralExpression>(Value(-1), "-1");
        } else {
            if (ctx.children[1]->getText() == ":" ||
                ctx.children[1]->getText() == "..") { // [:right]/[..right]
                left = std::make_unique<ParsedLiteralExpression>(Value(1), "1");
                right = transformExpression(*ctx.oC_Expression(0));
            } else { // [left:]/[left..]
                left = transformExpression(*ctx.oC_Expression(0));
                right = std::make_unique<ParsedLiteralExpression>(Value(-1), "-1");
            }
        }
        listSlice->addChild(std::move(left));
        listSlice->addChild(std::move(right));
        return listSlice;
    }
    // x[a]
    auto listExtract =
        std::make_unique<ParsedFunctionExpression>(ListExtractFunction::name, std::move(raw));
    listExtract->addChild(std::move(child));
    KU_ASSERT(ctx.oC_Expression().size() == 1);
    listExtract->addChild(transformExpression(*ctx.oC_Expression()[0]));
    return listExtract;
}

std::unique_ptr<ParsedExpression> Transformer::transformNullOperatorExpression(
    CypherParser::OC_NullOperatorExpressionContext& ctx,
    std::unique_ptr<ParsedExpression> propertyExpression) {
    auto rawExpression = propertyExpression->getRawName() + " " + ctx.getText();
    KU_ASSERT(ctx.IS() && ctx.NULL_());
    return ctx.NOT() ? std::make_unique<ParsedExpression>(ExpressionType::IS_NOT_NULL,
                           std::move(propertyExpression), rawExpression) :
                       std::make_unique<ParsedExpression>(ExpressionType::IS_NULL,
                           std::move(propertyExpression), rawExpression);
}

std::unique_ptr<ParsedExpression> Transformer::transformPropertyOrLabelsExpression(
    CypherParser::OC_PropertyOrLabelsExpressionContext& ctx) {
    auto atom = transformAtom(*ctx.oC_Atom());
    if (!ctx.oC_PropertyLookup().empty()) {
        auto lookUpCtx = ctx.oC_PropertyLookup(0);
        auto result = createPropertyExpression(*lookUpCtx, std::move(atom));
        for (auto i = 1u; i < ctx.oC_PropertyLookup().size(); ++i) {
            lookUpCtx = ctx.oC_PropertyLookup(i);
            result = createPropertyExpression(*lookUpCtx, std::move(result));
        }
        return result;
    }
    return atom;
}

std::unique_ptr<ParsedExpression> Transformer::transformAtom(CypherParser::OC_AtomContext& ctx) {
    if (ctx.oC_Literal()) {
        return transformLiteral(*ctx.oC_Literal());
    } else if (ctx.oC_Parameter()) {
        return transformParameterExpression(*ctx.oC_Parameter());
    } else if (ctx.oC_CaseExpression()) {
        return transformCaseExpression(*ctx.oC_CaseExpression());
    } else if (ctx.oC_ParenthesizedExpression()) {
        return transformParenthesizedExpression(*ctx.oC_ParenthesizedExpression());
    } else if (ctx.oC_FunctionInvocation()) {
        return transformFunctionInvocation(*ctx.oC_FunctionInvocation());
    } else if (ctx.oC_PathPatterns()) {
        return transformPathPattern(*ctx.oC_PathPatterns());
    } else if (ctx.oC_ExistCountSubquery()) {
        return transformExistCountSubquery(*ctx.oC_ExistCountSubquery());
    } else if (ctx.oC_Quantifier()) {
        return transformOcQuantifier(*ctx.oC_Quantifier());
    } else {
        KU_ASSERT(ctx.oC_Variable());
        return std::make_unique<ParsedVariableExpression>(transformVariable(*ctx.oC_Variable()),
            ctx.getText());
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformLiteral(
    CypherParser::OC_LiteralContext& ctx) {
    if (ctx.oC_NumberLiteral()) {
        return transformNumberLiteral(*ctx.oC_NumberLiteral(), false /*negative*/);
    } else if (ctx.oC_BooleanLiteral()) {
        return transformBooleanLiteral(*ctx.oC_BooleanLiteral());
    } else if (ctx.StringLiteral()) {
        return std::make_unique<ParsedLiteralExpression>(
            Value(LogicalType::STRING(), transformStringLiteral(*ctx.StringLiteral())),
            ctx.getText());
    } else if (ctx.NULL_()) {
        return std::make_unique<ParsedLiteralExpression>(Value::createNullValue(), ctx.getText());
    } else if (ctx.kU_StructLiteral()) {
        return transformStructLiteral(*ctx.kU_StructLiteral());
    } else {
        KU_ASSERT(ctx.oC_ListLiteral());
        return transformListLiteral(*ctx.oC_ListLiteral());
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformBooleanLiteral(
    CypherParser::OC_BooleanLiteralContext& ctx) {
    if (ctx.TRUE()) {
        return std::make_unique<ParsedLiteralExpression>(Value(true), ctx.getText());
    } else if (ctx.FALSE()) {
        return std::make_unique<ParsedLiteralExpression>(Value(false), ctx.getText());
    }
    KU_UNREACHABLE;
}

std::unique_ptr<ParsedExpression> Transformer::transformListLiteral(
    CypherParser::OC_ListLiteralContext& ctx) {
    auto listCreation =
        std::make_unique<ParsedFunctionExpression>(ListCreationFunction::name, ctx.getText());
    if (ctx.oC_Expression() == nullptr) { // empty list
        return listCreation;
    }
    listCreation->addChild(transformExpression(*ctx.oC_Expression()));
    for (auto& listEntry : ctx.kU_ListEntry()) {
        if (listEntry->oC_Expression() == nullptr) {
            auto nullValue = Value::createNullValue();
            listCreation->addChild(
                std::make_unique<ParsedLiteralExpression>(nullValue, nullValue.toString()));
        } else {
            listCreation->addChild(transformExpression(*listEntry->oC_Expression()));
        }
    }
    return listCreation;
}

std::unique_ptr<ParsedExpression> Transformer::transformStructLiteral(
    CypherParser::KU_StructLiteralContext& ctx) {
    auto structPack =
        std::make_unique<ParsedFunctionExpression>(StructPackFunctions::name, ctx.getText());
    for (auto& structField : ctx.kU_StructField()) {
        auto structExpr = transformExpression(*structField->oC_Expression());
        std::string paramName;
        if (structField->oC_SymbolicName()) {
            paramName = transformSymbolicName(*structField->oC_SymbolicName());
        } else {
            paramName = transformStringLiteral(*structField->StringLiteral());
        }
        structPack->addOptionalParams(std::move(paramName), std::move(structExpr));
    }
    return structPack;
}

std::unique_ptr<ParsedExpression> Transformer::transformParameterExpression(
    CypherParser::OC_ParameterContext& ctx) {
    auto parameterName =
        ctx.oC_SymbolicName() ? ctx.oC_SymbolicName()->getText() : ctx.DecimalInteger()->getText();
    return std::make_unique<ParsedParameterExpression>(parameterName, ctx.getText());
}

std::unique_ptr<ParsedExpression> Transformer::transformParenthesizedExpression(
    CypherParser::OC_ParenthesizedExpressionContext& ctx) {
    return transformExpression(*ctx.oC_Expression());
}

std::unique_ptr<ParsedExpression> Transformer::transformFunctionInvocation(
    CypherParser::OC_FunctionInvocationContext& ctx) {
    if (ctx.STAR()) {
        return std::make_unique<ParsedFunctionExpression>(CountStarFunction::name, ctx.getText());
    }
    std::string functionName;
    if (ctx.COUNT()) {
        functionName = "COUNT";
    } else if (ctx.CAST()) {
        functionName = "CAST";
    } else {
        functionName = transformFunctionName(*ctx.oC_FunctionName());
    }
    auto expression = std::make_unique<ParsedFunctionExpression>(functionName, ctx.getText(),
        ctx.DISTINCT() != nullptr);
    if (ctx.CAST()) {
        for (auto& functionParameter : ctx.kU_FunctionParameter()) {
            expression->addChild(transformFunctionParameterExpression(*functionParameter));
        }
        if (ctx.kU_DataType()) {
            expression->addChild(std::make_unique<ParsedLiteralExpression>(
                common::Value(transformDataType(*ctx.kU_DataType()))));
        }
    } else {
        for (auto& functionParameter : ctx.kU_FunctionParameter()) {
            auto parsedFunctionParameter = transformFunctionParameterExpression(*functionParameter);
            if (functionParameter->oC_SymbolicName()) {
                // Optional parameter
                expression->addOptionalParams(
                    transformSymbolicName(*functionParameter->oC_SymbolicName()),
                    std::move(parsedFunctionParameter));
            } else {
                expression->addChild(std::move(parsedFunctionParameter));
            }
        }
    }
    return expression;
}

std::string Transformer::transformFunctionName(CypherParser::OC_FunctionNameContext& ctx) {
    return transformSymbolicName(*ctx.oC_SymbolicName());
}

std::vector<std::string> Transformer::transformLambdaVariables(
    CypherParser::KU_LambdaVarsContext& ctx) {
    std::vector<std::string> lambdaVariables;
    lambdaVariables.reserve(ctx.oC_SymbolicName().size());
    for (auto& var : ctx.oC_SymbolicName()) {
        lambdaVariables.push_back(transformSymbolicName(*var));
    }
    return lambdaVariables;
}

std::unique_ptr<ParsedExpression> Transformer::transformLambdaParameter(
    CypherParser::KU_LambdaParameterContext& ctx) {
    auto vars = transformLambdaVariables(*ctx.kU_LambdaVars());
    auto lambdaOperation = transformExpression(*ctx.oC_Expression());
    return std::make_unique<ParsedLambdaExpression>(std::move(vars), std::move(lambdaOperation),
        ctx.getText());
}

std::unique_ptr<ParsedExpression> Transformer::transformFunctionParameterExpression(
    CypherParser::KU_FunctionParameterContext& ctx) {
    if (ctx.kU_LambdaParameter()) {
        return transformLambdaParameter(*ctx.kU_LambdaParameter());
    } else {
        auto expression = transformExpression(*ctx.oC_Expression());
        if (ctx.oC_SymbolicName()) {
            expression->setAlias(transformSymbolicName(*ctx.oC_SymbolicName()));
        }
        return expression;
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformPathPattern(
    CypherParser::OC_PathPatternsContext& ctx) {
    auto subquery = std::make_unique<ParsedSubqueryExpression>(SubqueryType::EXISTS, ctx.getText());
    auto patternElement = PatternElement(transformNodePattern(*ctx.oC_NodePattern()));
    for (auto& chain : ctx.oC_PatternElementChain()) {
        patternElement.addPatternElementChain(transformPatternElementChain(*chain));
    }
    subquery->addPatternElement(std::move(patternElement));
    return subquery;
}

std::unique_ptr<ParsedExpression> Transformer::transformExistCountSubquery(
    CypherParser::OC_ExistCountSubqueryContext& ctx) {
    auto type = ctx.EXISTS() ? SubqueryType::EXISTS : SubqueryType::COUNT;
    auto subquery = std::make_unique<ParsedSubqueryExpression>(type, ctx.getText());
    subquery->setPatternElements(transformPattern(*ctx.oC_Pattern()));
    if (ctx.oC_Where()) {
        subquery->setWhereClause(transformWhere(*ctx.oC_Where()));
    }
    if (ctx.kU_Hint()) {
        subquery->setHint(transformJoinHint(*ctx.kU_Hint()->kU_JoinNode()));
    }
    return subquery;
}

std::unique_ptr<ParsedExpression> Transformer::transformOcQuantifier(
    CypherParser::OC_QuantifierContext& ctx) {
    auto variable = transformVariable(*ctx.oC_FilterExpression()->oC_IdInColl()->oC_Variable());
    auto whereExpr = transformWhere(*ctx.oC_FilterExpression()->oC_Where());
    auto lambdaRaw = variable + "->" + whereExpr->toString();
    auto lambdaExpr = std::make_unique<ParsedLambdaExpression>(std::vector<std::string>{variable},
        std::move(whereExpr), lambdaRaw);
    std::string quantifierName;
    if (ctx.ALL()) {
        quantifierName = "ALL";
    } else if (ctx.ANY()) {
        quantifierName = "ANY";
    } else if (ctx.NONE()) {
        quantifierName = "NONE";
    } else if (ctx.SINGLE()) {
        quantifierName = "SINGLE";
    }
    auto listExpr = transformExpression(*ctx.oC_FilterExpression()->oC_IdInColl()->oC_Expression());
    return std::make_unique<ParsedFunctionExpression>(quantifierName, std::move(listExpr),
        std::move(lambdaExpr), ctx.getText());
}

std::unique_ptr<ParsedExpression> Transformer::createPropertyExpression(
    CypherParser::OC_PropertyKeyNameContext& ctx, std::unique_ptr<ParsedExpression> child) {
    auto key = transformPropertyKeyName(ctx);
    return std::make_unique<ParsedPropertyExpression>(key, std::move(child),
        child->toString() + "." + key);
}

std::unique_ptr<ParsedExpression> Transformer::createPropertyExpression(
    CypherParser::OC_PropertyLookupContext& ctx, std::unique_ptr<ParsedExpression> child) {
    auto key =
        ctx.STAR() ? InternalKeyword::STAR : transformPropertyKeyName(*ctx.oC_PropertyKeyName());
    return std::make_unique<ParsedPropertyExpression>(key, std::move(child),
        child->toString() + ctx.getText());
}

std::unique_ptr<ParsedExpression> Transformer::transformCaseExpression(
    CypherParser::OC_CaseExpressionContext& ctx) {
    std::unique_ptr<ParsedExpression> caseExpression = nullptr;
    std::unique_ptr<ParsedExpression> elseExpression = nullptr;
    if (ctx.ELSE()) {
        if (ctx.oC_Expression().size() == 1) {
            elseExpression = transformExpression(*ctx.oC_Expression(0));
        } else {
            KU_ASSERT(ctx.oC_Expression().size() == 2);
            caseExpression = transformExpression(*ctx.oC_Expression(0));
            elseExpression = transformExpression(*ctx.oC_Expression(1));
        }
    } else {
        if (ctx.oC_Expression().size() == 1) {
            caseExpression = transformExpression(*ctx.oC_Expression(0));
        }
    }
    auto parsedCaseExpression = std::make_unique<ParsedCaseExpression>(ctx.getText());
    parsedCaseExpression->setCaseExpression(std::move(caseExpression));
    parsedCaseExpression->setElseExpression(std::move(elseExpression));
    for (auto& caseAlternative : ctx.oC_CaseAlternative()) {
        parsedCaseExpression->addCaseAlternative(transformCaseAlternative(*caseAlternative));
    }
    return parsedCaseExpression;
}

ParsedCaseAlternative Transformer::transformCaseAlternative(
    CypherParser::OC_CaseAlternativeContext& ctx) {
    auto whenExpression = transformExpression(*ctx.oC_Expression(0));
    auto thenExpression = transformExpression(*ctx.oC_Expression(1));
    return ParsedCaseAlternative(std::move(whenExpression), std::move(thenExpression));
}

std::unique_ptr<ParsedExpression> Transformer::transformNumberLiteral(
    CypherParser::OC_NumberLiteralContext& ctx, bool negative) {
    if (ctx.oC_IntegerLiteral()) {
        return transformIntegerLiteral(*ctx.oC_IntegerLiteral(), negative);
    } else {
        KU_ASSERT(ctx.oC_DoubleLiteral());
        return transformDoubleLiteral(*ctx.oC_DoubleLiteral(), negative);
    }
}

std::unique_ptr<ParsedExpression> Transformer::transformProperty(
    CypherParser::OC_PropertyExpressionContext& ctx) {
    auto child = transformAtom(*ctx.oC_Atom());
    return createPropertyExpression(*ctx.oC_PropertyLookup(), std::move(child));
}

std::string Transformer::transformPropertyKeyName(CypherParser::OC_PropertyKeyNameContext& ctx) {
    return transformSchemaName(*ctx.oC_SchemaName());
}

std::unique_ptr<ParsedExpression> Transformer::transformIntegerLiteral(
    CypherParser::OC_IntegerLiteralContext& ctx, bool negative) {
    auto text = ctx.DecimalInteger()->getText();
    if (negative) {
        text = '-' + text;
    }
    ku_string_t literal{text.c_str(), text.length()};
    int64_t result = 0;
    if (function::CastString::tryCast(literal, result)) {
        return std::make_unique<ParsedLiteralExpression>(Value(result), ctx.getText());
    }
    int128_t result128 = 0;
    if (function::trySimpleIntegerCast(reinterpret_cast<const char*>(literal.getData()),
            literal.len, result128)) {
        return std::make_unique<ParsedLiteralExpression>(Value(result128), ctx.getText());
    }
    uint128_t resultu128 = 0;
    function::CastString::operation(literal, resultu128);
    return std::make_unique<ParsedLiteralExpression>(Value(resultu128), ctx.getText());
}

std::unique_ptr<ParsedExpression> Transformer::transformDoubleLiteral(
    CypherParser::OC_DoubleLiteralContext& ctx, bool negative) {
    auto text = ctx.ExponentDecimalReal() ? ctx.ExponentDecimalReal()->getText() :
                                            ctx.RegularDecimalReal()->getText();
    if (negative) {
        text = '-' + text;
    }
    ku_string_t literal{text.c_str(), text.length()};
    double result = 0;
    function::CastString::operation(literal, result);
    return std::make_unique<ParsedLiteralExpression>(Value(result), ctx.getText());
}

} // namespace parser
} // namespace lbug
