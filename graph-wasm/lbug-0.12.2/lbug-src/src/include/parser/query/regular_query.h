#pragma once

#include "common/types/types.h"
#include "parser/statement.h"
#include "single_query.h"

namespace lbug {
namespace parser {

class RegularQuery : public Statement {
    static constexpr common::StatementType type_ = common::StatementType::QUERY;

public:
    explicit RegularQuery(SingleQuery singleQuery) : Statement{type_} {
        singleQueries.push_back(std::move(singleQuery));
    }

    void addSingleQuery(SingleQuery singleQuery, bool isUnionAllQuery) {
        singleQueries.push_back(std::move(singleQuery));
        isUnionAll.push_back(isUnionAllQuery);
    }

    common::idx_t getNumSingleQueries() const { return singleQueries.size(); }

    const SingleQuery* getSingleQuery(common::idx_t singleQueryIdx) const {
        return &singleQueries[singleQueryIdx];
    }

    std::vector<bool> getIsUnionAll() const { return isUnionAll; }

private:
    std::vector<SingleQuery> singleQueries;
    std::vector<bool> isUnionAll;
};

} // namespace parser
} // namespace lbug
