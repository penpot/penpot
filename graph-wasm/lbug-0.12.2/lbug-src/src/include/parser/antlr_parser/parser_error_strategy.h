#pragma once

#include "antlr4-runtime.h" // IWYU pragma: keep; this is the public header.

namespace lbug {
namespace parser {

class ParserErrorStrategy : public antlr4::DefaultErrorStrategy {

protected:
    void reportNoViableAlternative(antlr4::Parser* recognizer,
        const antlr4::NoViableAltException& e) override;
};

} // namespace parser
} // namespace lbug
