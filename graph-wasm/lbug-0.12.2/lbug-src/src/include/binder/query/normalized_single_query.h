#pragma once

#include "binder/bound_statement_result.h"
#include "normalized_query_part.h"

namespace lbug {
namespace binder {

class NormalizedSingleQuery {
public:
    NormalizedSingleQuery() = default;
    DELETE_COPY_DEFAULT_MOVE(NormalizedSingleQuery);

    void appendQueryPart(NormalizedQueryPart queryPart) {
        queryParts.push_back(std::move(queryPart));
    }
    common::idx_t getNumQueryParts() const { return queryParts.size(); }
    NormalizedQueryPart* getQueryPartUnsafe(common::idx_t idx) { return &queryParts[idx]; }
    const NormalizedQueryPart* getQueryPart(common::idx_t idx) const { return &queryParts[idx]; }

    void setStatementResult(BoundStatementResult result) { statementResult = std::move(result); }
    const BoundStatementResult* getStatementResult() const { return &statementResult; }

private:
    std::vector<NormalizedQueryPart> queryParts;
    BoundStatementResult statementResult;
};

} // namespace binder
} // namespace lbug
