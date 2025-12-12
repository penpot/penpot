#pragma once

#include <cstdint>

namespace lbug {
namespace common {

enum class StatementType : uint8_t {
    QUERY = 0,
    CREATE_TABLE = 1,
    DROP = 2,
    ALTER = 3,
    COPY_TO = 19,
    COPY_FROM = 20,
    STANDALONE_CALL = 21,
    STANDALONE_CALL_FUNCTION = 22,
    EXPLAIN = 23,
    CREATE_MACRO = 24,
    TRANSACTION = 30,
    EXTENSION = 31,
    EXPORT_DATABASE = 32,
    IMPORT_DATABASE = 33,
    ATTACH_DATABASE = 34,
    DETACH_DATABASE = 35,
    USE_DATABASE = 36,
    CREATE_SEQUENCE = 37,
    CREATE_TYPE = 39,
    EXTENSION_CLAUSE = 40,
};

} // namespace common
} // namespace lbug
