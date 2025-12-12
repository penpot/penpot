#pragma once

#include "column_predicate.h"
#include "common/enums/expression_type.h"
#include "common/types/value/value.h"

namespace lbug {
namespace storage {

class ColumnConstantPredicate : public ColumnPredicate {
public:
    ColumnConstantPredicate(std::string columnName, common::ExpressionType expressionType,
        common::Value value)
        : ColumnPredicate{std::move(columnName), expressionType}, value{std::move(value)} {}

    common::ZoneMapCheckResult checkZoneMap(const MergedColumnChunkStats& stats) const override;

    std::string toString() override;

    std::unique_ptr<ColumnPredicate> copy() const override {
        return std::make_unique<ColumnConstantPredicate>(columnName, expressionType, value);
    }

private:
    common::Value value;
};

} // namespace storage
} // namespace lbug
