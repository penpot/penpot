#pragma once

#include <cstdint>

namespace lbug {
namespace common {

enum class ClauseType : uint8_t {
    // updating clause
    SET = 0,
    DELETE_ = 1, // winnt.h defines DELETE as a macro, so we use DELETE_ instead of DELETE.
    INSERT = 2,
    MERGE = 3,

    // reading clause
    MATCH = 10,
    UNWIND = 11,
    IN_QUERY_CALL = 12,
    TABLE_FUNCTION_CALL = 13,
    GDS_CALL = 14,
    LOAD_FROM = 15,
};

enum class MatchClauseType : uint8_t {
    MATCH = 0,
    OPTIONAL_MATCH = 1,
};

} // namespace common
} // namespace lbug
