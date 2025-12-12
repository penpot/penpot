#include "parser/parser.h"

// ANTLR4 generates code with unused parameters.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "cypher_lexer.h"
#pragma GCC diagnostic pop

#include "common/exception/parser.h"
#include "common/string_utils.h"
#include "parser/antlr_parser/lbug_cypher_parser.h"
#include "parser/antlr_parser/parser_error_listener.h"
#include "parser/antlr_parser/parser_error_strategy.h"
#include "parser/transformer.h"

using namespace antlr4;

namespace lbug {
namespace parser {

std::vector<std::shared_ptr<Statement>> Parser::parseQuery(std::string_view query,
    std::vector<extension::TransformerExtension*> transformerExtensions) {
    auto queryStr = std::string(query);
    queryStr = common::StringUtils::ltrim(queryStr);
    queryStr = common::StringUtils::ltrimNewlines(queryStr);
    // LCOV_EXCL_START
    // We should have enforced this in connection, but I also realize empty query will cause
    // antlr to hang. So enforce a duplicate check here.
    if (queryStr.empty()) {
        throw common::ParserException(
            "Cannot parse empty query. This should be handled in connection.");
    }
    // LCOV_EXCL_STOP

    auto inputStream = ANTLRInputStream(queryStr);
    auto parserErrorListener = ParserErrorListener();

    auto cypherLexer = CypherLexer(&inputStream);
    cypherLexer.removeErrorListeners();
    cypherLexer.addErrorListener(&parserErrorListener);
    auto tokens = CommonTokenStream(&cypherLexer);
    tokens.fill();

    auto lbugCypherParser = LbugCypherParser(&tokens);
    lbugCypherParser.removeErrorListeners();
    lbugCypherParser.addErrorListener(&parserErrorListener);
    lbugCypherParser.setErrorHandler(std::make_shared<ParserErrorStrategy>());

    Transformer transformer(*lbugCypherParser.ku_Statements(), std::move(transformerExtensions));
    return transformer.transform();
}

} // namespace parser
} // namespace lbug
