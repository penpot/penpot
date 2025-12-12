#pragma once

#include "function/table/bind_data.h"
#include "function/table/table_function.h"

namespace lbug {
namespace binder {

struct BoundTableScanInfo {
    function::TableFunction func;
    std::unique_ptr<function::TableFuncBindData> bindData;

    BoundTableScanInfo(function::TableFunction func,
        std::unique_ptr<function::TableFuncBindData> bindData)
        : func{std::move(func)}, bindData{std::move(bindData)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(BoundTableScanInfo);

private:
    BoundTableScanInfo(const BoundTableScanInfo& other)
        : func{other.func}, bindData{other.bindData->copy()} {}
};

} // namespace binder
} // namespace lbug
