#pragma once

#include "binder/expression/expression.h"
#include "common/enums/clause_type.h"

namespace lbug {
namespace binder {

class LBUG_API BoundReadingClause {
public:
    explicit BoundReadingClause(common::ClauseType clauseType) : clauseType{clauseType} {}
    DELETE_COPY_DEFAULT_MOVE(BoundReadingClause);
    virtual ~BoundReadingClause() = default;

    common::ClauseType getClauseType() const { return clauseType; }

    void setPredicate(std::shared_ptr<Expression> predicate_) { predicate = std::move(predicate_); }
    bool hasPredicate() const { return predicate != nullptr; }
    std::shared_ptr<Expression> getPredicate() const { return predicate; }
    expression_vector getConjunctivePredicates() const {
        return hasPredicate() ? predicate->splitOnAND() : expression_vector{};
    }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET* ptrCast() const {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

private:
    common::ClauseType clauseType;
    // Predicate in WHERE clause
    std::shared_ptr<Expression> predicate;
};

} // namespace binder
} // namespace lbug
