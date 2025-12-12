#pragma once

#include "common/case_insensitive_map.h"
#include "common/types/value/value.h"
namespace lbug {
namespace binder {

struct BoundQueryScanSourceInfo {
    common::case_insensitive_map_t<common::Value> options;

    explicit BoundQueryScanSourceInfo(common::case_insensitive_map_t<common::Value> options)
        : options{std::move(options)} {}
};

} // namespace binder
} // namespace lbug
