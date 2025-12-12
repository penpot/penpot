#pragma once

#include "binder/bound_database_statement.h"

namespace lbug {
namespace binder {

class BoundUseDatabase final : public BoundDatabaseStatement {
    static constexpr common::StatementType type_ = common::StatementType::USE_DATABASE;

public:
    explicit BoundUseDatabase(std::string dbName)
        : BoundDatabaseStatement{type_, std::move(dbName)} {}
};

} // namespace binder
} // namespace lbug
