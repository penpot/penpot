#pragma once

#include "binder/bound_table_scan_info.h"
#include "bound_reading_clause.h"

namespace lbug {
namespace binder {

class BoundLoadFrom final : public BoundReadingClause {
    static constexpr common::ClauseType clauseType_ = common::ClauseType::LOAD_FROM;

public:
    explicit BoundLoadFrom(BoundTableScanInfo info)
        : BoundReadingClause{clauseType_}, info{std::move(info)} {}

    const BoundTableScanInfo* getInfo() const { return &info; }

private:
    BoundTableScanInfo info;
};

} // namespace binder
} // namespace lbug
