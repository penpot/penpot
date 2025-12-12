#pragma once

#include "query_graph.h"

namespace lbug {
namespace binder {

class QueryGraphLabelAnalyzer {
public:
    explicit QueryGraphLabelAnalyzer(const main::ClientContext& clientContext, bool throwOnViolate)
        : throwOnViolate{throwOnViolate}, clientContext{clientContext} {}

    void pruneLabel(QueryGraph& graph) const;

private:
    void pruneNode(const QueryGraph& graph, NodeExpression& node) const;
    void pruneRel(RelExpression& rel) const;

private:
    bool throwOnViolate;
    const main::ClientContext& clientContext;
};

} // namespace binder
} // namespace lbug
