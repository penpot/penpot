#pragma once

#include "binder/expression/expression.h"
#include "common/cast.h"
#include "common/enums/zone_map_check_result.h"

namespace lbug {
namespace storage {

struct MergedColumnChunkStats;

class ColumnPredicate;
class LBUG_API ColumnPredicateSet {
public:
    ColumnPredicateSet() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(ColumnPredicateSet);

    void addPredicate(std::unique_ptr<ColumnPredicate> predicate) {
        predicates.push_back(std::move(predicate));
    }
    void tryAddPredicate(const binder::Expression& column, const binder::Expression& predicate);
    bool isEmpty() const { return predicates.empty(); }

    common::ZoneMapCheckResult checkZoneMap(const MergedColumnChunkStats& stats) const;

    std::string toString() const;

private:
    ColumnPredicateSet(const ColumnPredicateSet& other)
        : predicates{copyVector(other.predicates)} {}

private:
    std::vector<std::unique_ptr<ColumnPredicate>> predicates;
};

class LBUG_API ColumnPredicate {
public:
    ColumnPredicate(std::string columnName, common::ExpressionType expressionType)
        : columnName{std::move(columnName)}, expressionType(expressionType) {}

    virtual ~ColumnPredicate() = default;

    virtual common::ZoneMapCheckResult checkZoneMap(const MergedColumnChunkStats& stats) const = 0;

    virtual std::string toString();

    virtual std::unique_ptr<ColumnPredicate> copy() const = 0;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

protected:
    std::string columnName;
    common::ExpressionType expressionType;
};

struct LBUG_API ColumnPredicateUtil {
    static std::unique_ptr<ColumnPredicate> tryConvert(const binder::Expression& column,
        const binder::Expression& predicate);
};

} // namespace storage
} // namespace lbug
