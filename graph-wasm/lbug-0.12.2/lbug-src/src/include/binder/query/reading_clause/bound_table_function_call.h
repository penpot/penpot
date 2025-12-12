#pragma once

#include "binder/bound_table_scan_info.h"
#include "binder/query/reading_clause/bound_reading_clause.h"
#include "function/table/table_function.h"

namespace lbug {
namespace binder {

class LBUG_API BoundTableFunctionCall : public BoundReadingClause {
    static constexpr common::ClauseType clauseType_ = common::ClauseType::TABLE_FUNCTION_CALL;

public:
    explicit BoundTableFunctionCall(BoundTableScanInfo info)
        : BoundReadingClause{clauseType_}, info{std::move(info)} {}

    const function::TableFunction& getTableFunc() const { return info.func; }
    const function::TableFuncBindData* getBindData() const { return info.bindData.get(); }

private:
    BoundTableScanInfo info;
};

} // namespace binder
} // namespace lbug
