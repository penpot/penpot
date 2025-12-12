#include "binder/expression/expression_util.h"
#include "expression_evaluator/expression_evaluator_utils.h"
#include "function/string/functions/base_regexp_function.h"
#include "function/string/vector_string_functions.h"
#include "re2.h"

namespace lbug {
namespace function {

using namespace common;

struct RegexFullMatchBindData : public FunctionBindData {
    regex::RE2 pattern;

    explicit RegexFullMatchBindData(common::logical_type_vec_t paramTypes, std::string patternInStr)
        : FunctionBindData{std::move(paramTypes), common::LogicalType::BOOL()},
          pattern{patternInStr} {}

    std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<RegexFullMatchBindData>(copyVector(paramTypes), pattern.pattern());
    }
};

struct RegexpFullMatch {
    static void operation(common::ku_string_t& left, common::ku_string_t& right, uint8_t& result) {
        result = RE2::FullMatch(left.getAsString(),
            BaseRegexpOperation::parseCypherPattern(right.getAsString()));
    }
};

struct RegexpFullMatchStaticPattern : BaseRegexpOperation {
    static void operation(common::ku_string_t& left, common::ku_string_t& /*right*/,
        uint8_t& result, common::ValueVector& /*leftValueVector*/,
        common::ValueVector& /*rightValueVector*/, common::ValueVector& /*resultValueVector*/,
        void* dataPtr) {
        auto regexFullMatchBindData = reinterpret_cast<RegexFullMatchBindData*>(dataPtr);
        result = RE2::FullMatch(left.getAsString(), regexFullMatchBindData->pattern);
    }
};

static std::unique_ptr<FunctionBindData> regexFullMatchBindFunc(const ScalarBindFuncInput& input) {
    if (input.arguments[1]->expressionType == ExpressionType::LITERAL) {
        auto value = evaluator::ExpressionEvaluatorUtils::evaluateConstantExpression(
            input.arguments[1], input.context);
        input.definition->ptrCast<ScalarFunction>()->execFunc =
            ScalarFunction::BinaryExecWithBindData<ku_string_t, ku_string_t, uint8_t,
                RegexpFullMatchStaticPattern>;
        input.definition->ptrCast<ScalarFunction>()->selectFunc =
            ScalarFunction::BinarySelectWithBindData<ku_string_t, ku_string_t,
                RegexpFullMatchStaticPattern>;
        auto patternInStr = value.getValue<std::string>();
        return std::make_unique<RegexFullMatchBindData>(
            binder::ExpressionUtil::getDataTypes(input.arguments),
            BaseRegexpOperation::parseCypherPattern(patternInStr));
    } else {
        return FunctionBindData::getSimpleBindData(input.arguments, LogicalType::BOOL());
    }
}

function_set RegexpFullMatchFunction::getFunctionSet() {
    function_set functionSet;
    auto scalarFunc = make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::STRING},
        LogicalTypeID::BOOL,
        ScalarFunction::BinaryExecFunction<ku_string_t, ku_string_t, uint8_t, RegexpFullMatch>,
        ScalarFunction::BinarySelectFunction<ku_string_t, ku_string_t, RegexpFullMatch>);
    scalarFunc->bindFunc = regexFullMatchBindFunc;
    functionSet.emplace_back(std::move(scalarFunc));
    return functionSet;
}

} // namespace function
} // namespace lbug
