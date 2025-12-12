#pragma once

#include <cstdint>
#include <string>

namespace lbug {
namespace transaction {

enum class TransactionAction : uint8_t {
    BEGIN_READ = 0,
    BEGIN_WRITE = 1,
    COMMIT = 10,
    ROLLBACK = 20,
    CHECKPOINT = 30,
};

class TransactionActionUtils {
public:
    static std::string toString(TransactionAction action);
};

} // namespace transaction
} // namespace lbug
