#include "parser/create_macro.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::vector<std::string> Transformer::transformPositionalArgs(
    CypherParser::KU_PositionalArgsContext& ctx) {
    std::vector<std::string> positionalArgs;
    for (auto& positionalArg : ctx.oC_SymbolicName()) {
        positionalArgs.push_back(transformSymbolicName(*positionalArg));
    }
    return positionalArgs;
}

std::unique_ptr<Statement> Transformer::transformCreateMacro(
    CypherParser::KU_CreateMacroContext& ctx) {
    auto macroName = transformFunctionName(*ctx.oC_FunctionName());
    auto macroExpression = transformExpression(*ctx.oC_Expression());
    std::vector<std::string> positionalArgs;
    if (ctx.kU_PositionalArgs()) {
        positionalArgs = transformPositionalArgs(*ctx.kU_PositionalArgs());
    }
    default_macro_args defaultArgs;
    for (auto& defaultArg : ctx.kU_DefaultArg()) {
        defaultArgs.emplace_back(transformSymbolicName(*defaultArg->oC_SymbolicName()),
            transformLiteral(*defaultArg->oC_Literal()));
    }
    return std::make_unique<CreateMacro>(std::move(macroName), std::move(macroExpression),
        std::move(positionalArgs), std::move(defaultArgs));
}

} // namespace parser
} // namespace lbug
