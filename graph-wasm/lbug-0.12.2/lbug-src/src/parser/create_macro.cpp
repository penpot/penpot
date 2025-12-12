#include "parser/create_macro.h"

namespace lbug {
namespace parser {

std::vector<std::pair<std::string, ParsedExpression*>> CreateMacro::getDefaultArgs() const {
    std::vector<std::pair<std::string, ParsedExpression*>> defaultArgsToReturn;
    for (auto& defaultArg : defaultArgs) {
        defaultArgsToReturn.emplace_back(defaultArg.first, defaultArg.second.get());
    }
    return defaultArgsToReturn;
}

} // namespace parser
} // namespace lbug
