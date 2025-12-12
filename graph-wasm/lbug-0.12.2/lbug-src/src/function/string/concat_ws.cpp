#include "common/exception/binder.h"
#include "function/string/vector_string_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    if (input.arguments.size() < 2) {
        throw BinderException{stringFormat("concat_ws expects at least two parameters. Got: {}.",
            input.arguments.size())};
    }
    for (auto i = 0u; i < input.arguments.size(); i++) {
        auto& argument = input.arguments[i];
        if (argument->getDataType().getLogicalTypeID() == LogicalTypeID::ANY) {
            argument->cast(LogicalType::STRING());
        }
        if (argument->getDataType() != LogicalType::STRING()) {
            throw BinderException{stringFormat("concat_ws expects all string parameters. Got: {}.",
                argument->getDataType().toString())};
        }
    }
    return FunctionBindData::getSimpleBindData(input.arguments, LogicalType::STRING());
}

using handle_separator_func_t = std::function<void()>;
using handle_element_func_t = std::function<void(const ku_string_t&)>;

static void iterateParams(const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
    const std::vector<common::SelectionVector*>& parameterSelVectors, sel_t pos,
    handle_separator_func_t handleSeparatorFunc, handle_element_func_t handleElementFunc) {
    bool isPrevNull = false;
    for (auto i = 1u; i < parameters.size(); i++) {
        const auto& parameter = parameters[i];
        const auto& parameterSelVector = *parameterSelVectors[i];
        auto paramPos = parameter->state->isFlat() ? parameterSelVector[0] : pos;
        if (parameter->isNull(paramPos)) {
            isPrevNull = true;
            continue;
        }
        if (i != 1u && !isPrevNull) {
            handleSeparatorFunc();
        }
        handleElementFunc(parameter->getValue<ku_string_t>(paramPos));
        isPrevNull = false;
    }
}

void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
    const std::vector<common::SelectionVector*>& parameterSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* /*dataPtr*/) {
    result.resetAuxiliaryBuffer();
    for (auto selectedPos = 0u; selectedPos < resultSelVector->getSelSize(); ++selectedPos) {
        auto pos = (*resultSelVector)[selectedPos];
        auto separatorPos = parameters[0]->state->isFlat() ? (*parameterSelVectors[0])[0] : pos;
        if (parameters[0]->isNull(separatorPos)) {
            result.setNull(pos, true /* isNull */);
            continue;
        }
        auto separator = parameters[0]->getValue<ku_string_t>(separatorPos);
        auto len = 0u;
        bool isPrevNull = false;
        iterateParams(
            parameters, parameterSelVectors, pos, [&]() { len += separator.len; },
            [&](const ku_string_t& str) { len += str.len; });
        for (auto i = 1u; i < parameters.size(); i++) {
            const auto& parameter = parameters[i];
            const auto& parameterSelVector = *parameterSelVectors[i];
            auto paramPos = parameter->state->isFlat() ? parameterSelVector[0] : pos;
            if (parameter->isNull(paramPos)) {
                isPrevNull = true;
                continue;
            }
            if (i != 1u && !isPrevNull) {}

            isPrevNull = false;
        }
        common::ku_string_t resultStr;
        StringVector::reserveString(&result, resultStr, len);
        auto resultBuffer = resultStr.getData();
        iterateParams(
            parameters, parameterSelVectors, pos,
            [&]() {
                memcpy((void*)resultBuffer, (void*)separator.getData(), separator.len);
                resultBuffer += separator.len;
            },
            [&](const ku_string_t& str) {
                memcpy((void*)resultBuffer, (void*)str.getData(), str.len);
                resultBuffer += str.len;
            });
        memcpy(resultStr.prefix, resultStr.getData(),
            std::min<uint64_t>(resultStr.len, ku_string_t::PREFIX_LENGTH));
        KU_ASSERT(resultBuffer - resultStr.getData() == len);
        result.setNull(pos, false /* isNull */);
        result.setValue(pos, resultStr);
    }
}

function_set ConcatWSFunction::getFunctionSet() {
    function_set functionSet;
    auto func = make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::STRING},
        LogicalTypeID::STRING, execFunc);
    func->bindFunc = bindFunc;
    func->isVarLength = true;
    functionSet.push_back(std::move(func));
    return functionSet;
}

} // namespace function
} // namespace lbug
