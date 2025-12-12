#pragma once

#include "parser/query/reading_clause/reading_clause.h"
#include "parser/query/return_with_clause/with_clause.h"
#include "parser/query/updating_clause/updating_clause.h"

namespace lbug {
namespace parser {

class QueryPart {
public:
    explicit QueryPart(WithClause withClause) : withClause{std::move(withClause)} {}

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

    inline const WithClause* getWithClause() const { return &withClause; }

private:
    std::vector<std::unique_ptr<ReadingClause>> readingClauses;
    std::vector<std::unique_ptr<UpdatingClause>> updatingClauses;
    WithClause withClause;
};

} // namespace parser
} // namespace lbug
