#pragma once

#include "binder/bound_statement.h"
#include "function/export/export_function.h"

namespace lbug {
namespace binder {

class BoundCopyTo final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::COPY_TO;

public:
    BoundCopyTo(std::unique_ptr<function::ExportFuncBindData> bindData,
        function::ExportFunction exportFunc, std::unique_ptr<BoundStatement> query)
        : BoundStatement{type_, BoundStatementResult::createEmptyResult()},
          bindData{std::move(bindData)}, exportFunc{std::move(exportFunc)},
          query{std::move(query)} {}

    std::unique_ptr<function::ExportFuncBindData> getBindData() const { return bindData->copy(); }

    function::ExportFunction getExportFunc() const { return exportFunc; }

    const BoundStatement* getRegularQuery() const { return query.get(); }

private:
    std::unique_ptr<function::ExportFuncBindData> bindData;
    function::ExportFunction exportFunc;
    std::unique_ptr<BoundStatement> query;
};

} // namespace binder
} // namespace lbug
