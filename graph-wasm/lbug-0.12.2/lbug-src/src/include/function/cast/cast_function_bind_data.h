#pragma once

#include "common/copier_config/csv_reader_config.h"
#include "function/function.h"

namespace lbug {
namespace function {

struct CastFunctionBindData : public FunctionBindData {
    // We don't allow configuring delimiters, ... in CAST function.
    // For performance purpose, we generate a default option object during binding time.
    common::CSVOption option;
    // TODO(Mahn): the following field should be removed once we refactor fixed list.
    uint64_t numOfEntries;

    explicit CastFunctionBindData(common::LogicalType dataType)
        : FunctionBindData{std::move(dataType)}, numOfEntries{0} {}

    inline std::unique_ptr<FunctionBindData> copy() const override {
        auto result = std::make_unique<CastFunctionBindData>(resultType.copy());
        result->numOfEntries = numOfEntries;
        result->option = option.copy();
        return result;
    }
};

} // namespace function
} // namespace lbug
