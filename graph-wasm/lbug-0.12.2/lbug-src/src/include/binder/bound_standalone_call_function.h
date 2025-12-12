#pragma once

#include "binder/bound_statement.h"
#include "bound_table_scan_info.h"

namespace lbug {
namespace binder {

class BoundStandaloneCallFunction final : public BoundStatement {
    static constexpr common::StatementType statementType =
        common::StatementType::STANDALONE_CALL_FUNCTION;

public:
    explicit BoundStandaloneCallFunction(BoundTableScanInfo info)
        : BoundStatement{statementType, BoundStatementResult::createEmptyResult()},
          info{std::move(info)} {}

    const function::TableFunction& getTableFunction() const { return info.func; }

    const function::TableFuncBindData* getBindData() const { return info.bindData.get(); }

private:
    BoundTableScanInfo info;
};

} // namespace binder
} // namespace lbug
