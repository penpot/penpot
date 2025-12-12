#include "parser/expression/parsed_function_expression.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

std::unique_ptr<ParsedFunctionExpression> ParsedFunctionExpression::deserialize(
    Deserializer& deserializer) {
    bool isDistinct = false;
    deserializer.deserializeValue(isDistinct);
    std::string functionName;
    deserializer.deserializeValue(functionName);
    std::vector<std::string> optionalArguments;
    deserializer.deserializeVector(optionalArguments);
    auto result = std::make_unique<ParsedFunctionExpression>(std::move(functionName), isDistinct);
    result->setOptionalArguments(std::move(optionalArguments));
    return result;
}

void ParsedFunctionExpression::serializeInternal(Serializer& serializer) const {
    serializer.serializeValue(isDistinct);
    serializer.serializeValue(functionName);
    serializer.serializeVector(optionalArguments);
}

} // namespace parser
} // namespace lbug
