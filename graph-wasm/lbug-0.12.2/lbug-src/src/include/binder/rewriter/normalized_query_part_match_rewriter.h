#pragma once

#include "binder/bound_statement_visitor.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace binder {

// Merge consecutive match pattern in a query part. E.g.
// MATCH (a) WHERE a.ID = 0
// MATCH (b) WHERE b.ID = 1
// MATCH (a)-[]->(b)
// will be rewritten as
// MATCH (a)-[]->(b) WHERE a.ID = 0 AND b.ID = 1
// This rewrite does not apply to MATCH with HINT or OPTIONAL MATCH
class NormalizedQueryPartMatchRewriter final : public BoundStatementVisitor {
public:
    explicit NormalizedQueryPartMatchRewriter(main::ClientContext* clientContext)
        : clientContext{clientContext} {}

private:
    void visitQueryPartUnsafe(NormalizedQueryPart& queryPart) override;

private:
    main::ClientContext* clientContext;
};

} // namespace binder
} // namespace lbug
