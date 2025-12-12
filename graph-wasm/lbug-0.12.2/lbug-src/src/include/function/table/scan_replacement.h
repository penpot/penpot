#pragma once

#include "function/table/bind_input.h"
#include "function/table/table_function.h"

namespace lbug {
namespace function {

struct ScanReplacementData {
    TableFunction func;
    TableFuncBindInput bindInput;
};

using scan_replace_handle_t = uint8_t*;
using handle_lookup_func_t = std::function<std::vector<scan_replace_handle_t>(const std::string&)>;
using scan_replace_func_t =
    std::function<std::unique_ptr<ScanReplacementData>(std::span<scan_replace_handle_t>)>;

struct ScanReplacement {
    explicit ScanReplacement(handle_lookup_func_t lookupFunc, scan_replace_func_t replaceFunc)
        : lookupFunc(std::move(lookupFunc)), replaceFunc{std::move(replaceFunc)} {}

    handle_lookup_func_t lookupFunc;
    scan_replace_func_t replaceFunc;
};

} // namespace function
} // namespace lbug
