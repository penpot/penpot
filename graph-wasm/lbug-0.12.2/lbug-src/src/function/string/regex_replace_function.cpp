#include "binder/expression/expression_util.h"
#include "common/exception/binder.h"
#include "expression_evaluator/expression_evaluator_utils.h"
#include "function/string/functions/base_regexp_function.h"
#include "function/string/vector_string_functions.h"
#include "re2.h"

namespace lbug {
namespace function {

using namespace common;

using re2_replace_func_t =
    std::function<void(std::string* str, const RE2& re, const regex::StringPiece& rewrite)>;

struct RegexReplaceBindData : public FunctionBindData {
    re2_replace_func_t replaceFunc;

    RegexReplaceBindData(common::logical_type_vec_t paramTypes, re2_replace_func_t replaceFunc)
        : FunctionBindData{std::move(paramTypes), common::LogicalType::STRING()},
          replaceFunc{std::move(replaceFunc)} {}

    std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<RegexReplaceBindData>(copyVector(paramTypes), replaceFunc);
    }
};

struct RegexpReplace {
    static void operation(common::ku_string_t& value, common::ku_string_t& pattern,
        common::ku_string_t& replacement, common::ku_string_t& result,
        common::ValueVector& resultValueVector, void* dataPtr) {
        auto bindData = reinterpret_cast<RegexReplaceBindData*>(dataPtr);
        std::string resultStr = value.getAsString();
        RE2 re2Pattern{pattern.getAsString()};
        bindData->replaceFunc(&resultStr, re2Pattern, replacement.getAsString());
        BaseRegexpOperation::copyToLbugString(resultStr, result, resultValueVector);
    }
};

struct RegexReplaceBindDataStaticPattern : public RegexReplaceBindData {
    regex::RE2 pattern;

    RegexReplaceBindDataStaticPattern(common::logical_type_vec_t paramTypes,
        re2_replace_func_t replaceFunc, std::string patternInStr)
        : RegexReplaceBindData{std::move(paramTypes), std::move(replaceFunc)},
          pattern{patternInStr} {}

    std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<RegexReplaceBindDataStaticPattern>(copyVector(paramTypes),
            replaceFunc, pattern.pattern());
    }
};

struct RegexpReplaceStaticPattern {
    static void operation(common::ku_string_t& value, common::ku_string_t& /*pattern*/,
        common::ku_string_t& replacement, common::ku_string_t& result,
        common::ValueVector& resultValueVector, void* dataPtr) {
        auto bindData = reinterpret_cast<RegexReplaceBindDataStaticPattern*>(dataPtr);
        auto resultStr = value.getAsString();
        bindData->replaceFunc(&resultStr, bindData->pattern, replacement.getAsString());
        BaseRegexpOperation::copyToLbugString(resultStr, result, resultValueVector);
    }
};

static re2_replace_func_t bindReplaceFunc(const binder::expression_vector& expr) {
    re2_replace_func_t result;
    switch (expr.size()) {
    case 3: {
        result = RE2::Replace;
    } break;
    case 4: {
        result = RE2::GlobalReplace;
    } break;
    default:
        KU_UNREACHABLE;
    }
    return result;
}

template<typename OP>
scalar_func_exec_t getExecFunc(const binder::expression_vector& expr) {
    scalar_func_exec_t execFunc;
    switch (expr.size()) {
    case 3: {
        execFunc = ScalarFunction::TernaryRegexExecFunction<ku_string_t, ku_string_t, ku_string_t,
            ku_string_t, OP>;
    } break;
    case 4: {
        auto option = expr[3];
        binder::ExpressionUtil::validateExpressionType(*option, ExpressionType::LITERAL);
        binder::ExpressionUtil::validateDataType(*option, LogicalType::STRING());
        auto optionVal = binder::ExpressionUtil::getLiteralValue<std::string>(*option);
        if (optionVal != RegexpReplaceFunction::GLOBAL_REPLACE_OPTION) {
            throw common::BinderException{
                "regex_replace can only support global replace option: g."};
        }
        execFunc = ScalarFunction::TernaryRegexExecFunction<ku_string_t, ku_string_t, ku_string_t,
            ku_string_t, OP>;
    } break;
    default:
        KU_UNREACHABLE;
    }
    return execFunc;
}

std::unique_ptr<FunctionBindData> bindFunc(ScalarBindFuncInput input) {
    auto definition = input.definition->ptrCast<ScalarFunction>();
    re2_replace_func_t replaceFunc = bindReplaceFunc(input.arguments);
    if (input.arguments[1]->expressionType == ExpressionType::LITERAL) {
        definition->execFunc = getExecFunc<RegexpReplaceStaticPattern>(input.arguments);
        auto value = evaluator::ExpressionEvaluatorUtils::evaluateConstantExpression(
            input.arguments[1], input.context);
        return std::make_unique<RegexReplaceBindDataStaticPattern>(
            binder::ExpressionUtil::getDataTypes(input.arguments), std::move(replaceFunc),
            BaseRegexpOperation::parseCypherPattern(value.getValue<std::string>()));
    } else {
        definition->execFunc = getExecFunc<RegexpReplace>(input.arguments);
        return std::make_unique<RegexReplaceBindData>(
            binder::ExpressionUtil::getDataTypes(input.arguments), std::move(replaceFunc));
    }
}

function_set RegexpReplaceFunction::getFunctionSet() {
    function_set functionSet;
    std::unique_ptr<ScalarFunction> func;
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::STRING,
            LogicalTypeID::STRING, LogicalTypeID::STRING},
        LogicalTypeID::STRING);
    func->bindFunc = bindFunc;
    functionSet.emplace_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::STRING,
            LogicalTypeID::STRING},
        LogicalTypeID::STRING);
    func->bindFunc = bindFunc;
    functionSet.emplace_back(std::move(func));
    return functionSet;
}

} // namespace function
} // namespace lbug
