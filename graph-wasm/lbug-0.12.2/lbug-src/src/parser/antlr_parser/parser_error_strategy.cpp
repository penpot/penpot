#include "parser/antlr_parser/parser_error_strategy.h"

namespace lbug {
namespace parser {

void ParserErrorStrategy::reportNoViableAlternative(antlr4::Parser* recognizer,
    const antlr4::NoViableAltException& e) {
    auto tokens = recognizer->getTokenStream();
    auto errorMsg =
        tokens ?
            antlr4::Token::EOF == e.getStartToken()->getType() ?
            "Unexpected end of input" :
            "Invalid input <" + tokens->getText(e.getStartToken(), e.getOffendingToken()) + ">" :
            "Unknown input";
    auto expectedRuleName = recognizer->getRuleNames()[recognizer->getContext()->getRuleIndex()];
    errorMsg += ": expected rule " + expectedRuleName;
    recognizer->notifyErrorListeners(e.getOffendingToken(), errorMsg, make_exception_ptr(e));
}

} // namespace parser
} // namespace lbug
