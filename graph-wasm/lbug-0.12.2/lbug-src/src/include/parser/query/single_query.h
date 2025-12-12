#pragma once

#include <optional>

#include "common/assert.h"
#include "common/copy_constructors.h"
#include "query_part.h"

namespace lbug {
namespace parser {

class SingleQuery {
public:
    SingleQuery() = default;
    DELETE_COPY_DEFAULT_MOVE(SingleQuery);

    inline void addQueryPart(QueryPart queryPart) { queryParts.push_back(std::move(queryPart)); }
    inline uint32_t getNumQueryParts() const { return queryParts.size(); }
    inline const QueryPart* getQueryPart(uint32_t idx) const { return &queryParts[idx]; }

    inline uint32_t getNumUpdatingClauses() const { return updatingClauses.size(); }
    inline UpdatingClause* getUpdatingClause(uint32_t idx) const {
        return updatingClauses[idx].get();
    }
    inline void addUpdatingClause(std::unique_ptr<UpdatingClause> updatingClause) {
        updatingClauses.push_back(std::move(updatingClause));
    }

    inline uint32_t getNumReadingClauses() const { return readingClauses.size(); }
    inline ReadingClause* getReadingClause(uint32_t idx) const { return readingClauses[idx].get(); }
    inline void addReadingClause(std::unique_ptr<ReadingClause> readingClause) {
        readingClauses.push_back(std::move(readingClause));
    }

    inline void setReturnClause(ReturnClause clause) { returnClause = std::move(clause); }
    inline bool hasReturnClause() const { return returnClause.has_value(); }
    inline const ReturnClause* getReturnClause() const {
        KU_ASSERT(returnClause.has_value());
        return &returnClause.value();
    }

private:
    std::vector<QueryPart> queryParts;
    std::vector<std::unique_ptr<ReadingClause>> readingClauses;
    std::vector<std::unique_ptr<UpdatingClause>> updatingClauses;
    std::optional<ReturnClause> returnClause;
};

} // namespace parser
} // namespace lbug
