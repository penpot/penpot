#pragma once

#include <string>

#include "parser/database_statement.h"

namespace lbug {
namespace parser {

class DetachDatabase final : public DatabaseStatement {
    static constexpr common::StatementType type_ = common::StatementType::DETACH_DATABASE;

public:
    explicit DetachDatabase(std::string dbName) : DatabaseStatement{type_, std::move(dbName)} {}
};

} // namespace parser
} // namespace lbug
