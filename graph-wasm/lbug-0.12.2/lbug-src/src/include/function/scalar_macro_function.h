#pragma once

#include <unordered_map>

#include "parser/create_macro.h"

namespace lbug {
namespace function {

using macro_parameter_value_map = std::unordered_map<std::string, parser::ParsedExpression*>;

struct ScalarMacroFunction {
    std::unique_ptr<parser::ParsedExpression> expression;
    std::vector<std::string> positionalArgs;
    parser::default_macro_args defaultArgs;

    ScalarMacroFunction() = default;

    ScalarMacroFunction(std::unique_ptr<parser::ParsedExpression> expression,
        std::vector<std::string> positionalArgs, parser::default_macro_args defaultArgs)
        : expression{std::move(expression)}, positionalArgs{std::move(positionalArgs)},
          defaultArgs{std::move(defaultArgs)} {}

    std::string getDefaultParameterName(uint64_t idx) const { return defaultArgs[idx].first; }

    uint64_t getNumArgs() const { return positionalArgs.size() + defaultArgs.size(); }

    std::vector<std::string> getPositionalArgs() const { return positionalArgs; }

    macro_parameter_value_map getDefaultParameterVals() const;

    std::unique_ptr<ScalarMacroFunction> copy() const;

    void serialize(common::Serializer& serializer) const;

    std::string toCypher(const std::string& name) const;

    static std::unique_ptr<ScalarMacroFunction> deserialize(common::Deserializer& deserializer);
};

} // namespace function
} // namespace lbug
