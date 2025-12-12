#pragma once

#include <memory>

#include "parser/statement.h"

namespace antlr4 {
class ParserRuleContext;
}

namespace lbug {
namespace extension {

class LBUG_API TransformerExtension {
public:
    TransformerExtension() {}

    virtual ~TransformerExtension() = default;

    virtual std::unique_ptr<parser::Statement> transform(antlr4::ParserRuleContext* context) = 0;
};

} // namespace extension
} // namespace lbug
