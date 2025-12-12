#pragma once

#include "parser/parsed_statement_visitor.h"

namespace lbug {
namespace parser {

class StandaloneCallRewriter final : public StatementVisitor {
public:
    explicit StandaloneCallRewriter(main::ClientContext* context, bool allowRewrite)
        : StatementVisitor{}, rewriteQuery{}, context{context}, singleStatement{allowRewrite} {}

    std::string getRewriteQuery(const Statement& statement);

private:
    void visitStandaloneCallFunction(const Statement& statement) override;

private:
    std::string rewriteQuery;
    main::ClientContext* context;
    bool singleStatement;
};

} // namespace parser
} // namespace lbug
