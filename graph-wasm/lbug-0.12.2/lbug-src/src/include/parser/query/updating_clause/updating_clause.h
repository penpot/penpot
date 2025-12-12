#pragma once

#include "common/cast.h"
#include "common/enums/clause_type.h"

namespace lbug {
namespace parser {

class UpdatingClause {
public:
    explicit UpdatingClause(common::ClauseType clauseType) : clauseType{clauseType} {};
    virtual ~UpdatingClause() = default;

    common::ClauseType getClauseType() const { return clauseType; }

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

private:
    common::ClauseType clauseType;
};

} // namespace parser
} // namespace lbug
