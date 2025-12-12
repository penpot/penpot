#pragma once

#include "binder/bound_statement_visitor.h"

namespace lbug {
namespace binder {

// WithClauseProjectionRewriter first analyze the properties need to be scanned for each query. And
// then rewrite node/rel expression in WITH clause as their properties. So We avoid eagerly evaluate
// node/rel in WITH clause projection. E.g.
// MATCH (a) WITH a MATCH (a)->(b);
// will be rewritten as
// MATCH (a) WITH a._id MATCH (a)->(b);
class WithClauseProjectionRewriter final : public BoundStatementVisitor {
public:
    void visitSingleQueryUnsafe(NormalizedSingleQuery& singleQuery) override;
};

} // namespace binder
} // namespace lbug
