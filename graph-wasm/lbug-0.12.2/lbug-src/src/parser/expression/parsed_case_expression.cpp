#include "parser/expression/parsed_case_expression.h"

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"

using namespace lbug::common;

namespace lbug {
namespace parser {

void ParsedCaseAlternative::serialize(Serializer& serializer) const {
    whenExpression->serialize(serializer);
    thenExpression->serialize(serializer);
}

ParsedCaseAlternative ParsedCaseAlternative::deserialize(Deserializer& deserializer) {
    auto whenExpression = ParsedExpression::deserialize(deserializer);
    auto thenExpression = ParsedExpression::deserialize(deserializer);
    return ParsedCaseAlternative(std::move(whenExpression), std::move(thenExpression));
}

std::unique_ptr<ParsedCaseExpression> ParsedCaseExpression::deserialize(
    Deserializer& deserializer) {
    std::unique_ptr<ParsedExpression> caseExpression;
    deserializer.deserializeOptionalValue(caseExpression);
    std::vector<ParsedCaseAlternative> caseAlternatives;
    deserializer.deserializeVector<ParsedCaseAlternative>(caseAlternatives);
    std::unique_ptr<ParsedExpression> elseExpression;
    deserializer.deserializeOptionalValue(elseExpression);
    return std::make_unique<ParsedCaseExpression>(std::move(caseExpression),
        std::move(caseAlternatives), std::move(elseExpression));
}

std::unique_ptr<ParsedExpression> ParsedCaseExpression::copy() const {
    std::vector<ParsedCaseAlternative> caseAlternativesCopy;
    caseAlternativesCopy.reserve(caseAlternatives.size());
    for (auto& caseAlternative : caseAlternatives) {
        caseAlternativesCopy.push_back(caseAlternative);
    }
    return std::make_unique<ParsedCaseExpression>(alias, rawName, copyVector(children),
        caseExpression ? caseExpression->copy() : nullptr, std::move(caseAlternativesCopy),
        elseExpression ? elseExpression->copy() : nullptr);
}

void ParsedCaseExpression::serializeInternal(Serializer& serializer) const {
    serializer.serializeOptionalValue(caseExpression);
    serializer.serializeVector(caseAlternatives);
    serializer.serializeOptionalValue(elseExpression);
}

} // namespace parser
} // namespace lbug
