#pragma once

#include "common/cast.h"
#include "common/enums/clause_type.h"

namespace lbug {
namespace binder {

class BoundUpdatingClause {
public:
    explicit BoundUpdatingClause(common::ClauseType clauseType) : clauseType{clauseType} {}
    virtual ~BoundUpdatingClause() = default;

    common::ClauseType getClauseType() const { return clauseType; }

    template<class TARGET>
    TARGET& cast() const {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

private:
    common::ClauseType clauseType;
};

} // namespace binder
} // namespace lbug
