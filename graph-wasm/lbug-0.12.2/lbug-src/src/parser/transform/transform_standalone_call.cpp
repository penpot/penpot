#include "parser/standalone_call.h"
#include "parser/standalone_call_function.h"
#include "parser/transformer.h"

namespace lbug {
namespace parser {

std::unique_ptr<Statement> Transformer::transformStandaloneCall(
    CypherParser::KU_StandaloneCallContext& ctx) {
    if (ctx.oC_FunctionInvocation()) {
        return std::make_unique<StandaloneCallFunction>(
            transformFunctionInvocation(*ctx.oC_FunctionInvocation()));
    } else {
        auto optionName = transformSymbolicName(*ctx.oC_SymbolicName());
        auto parameter = transformExpression(*ctx.oC_Expression());
        return std::make_unique<StandaloneCall>(std::move(optionName), std::move(parameter));
    }
}

} // namespace parser
} // namespace lbug
