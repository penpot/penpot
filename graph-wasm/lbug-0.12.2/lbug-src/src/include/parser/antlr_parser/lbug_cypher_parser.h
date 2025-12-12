#pragma once

// ANTLR4 generates code with unused parameters.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "cypher_parser.h"
#pragma GCC diagnostic pop

namespace lbug {
namespace parser {

class LbugCypherParser : public CypherParser {

public:
    explicit LbugCypherParser(antlr4::TokenStream* input) : CypherParser(input) {}

    void notifyQueryNotConcludeWithReturn(antlr4::Token* startToken) override;

    void notifyNodePatternWithoutParentheses(std::string nodeName,
        antlr4::Token* startToken) override;

    void notifyInvalidNotEqualOperator(antlr4::Token* startToken) override;

    void notifyEmptyToken(antlr4::Token* startToken) override;

    void notifyReturnNotAtEnd(antlr4::Token* startToken) override;

    void notifyNonBinaryComparison(antlr4::Token* startToken) override;
};

} // namespace parser
} // namespace lbug
