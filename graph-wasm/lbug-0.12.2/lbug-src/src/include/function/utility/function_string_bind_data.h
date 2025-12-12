#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct FunctionStringBindData : public FunctionBindData {
    explicit FunctionStringBindData(std::string str)
        : FunctionBindData{common::LogicalType::STRING()}, str{std::move(str)} {}

    std::string str;

    inline std::unique_ptr<FunctionBindData> copy() const override {
        return std::make_unique<FunctionStringBindData>(str);
    }
};

} // namespace function
} // namespace lbug
