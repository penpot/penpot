#pragma once

#include "binder/bound_statement.h"

namespace lbug {
namespace binder {

class BoundCreateType final : public BoundStatement {
    static constexpr common::StatementType type_ = common::StatementType::CREATE_TYPE;

public:
    explicit BoundCreateType(std::string name, common::LogicalType type)
        : BoundStatement{type_, BoundStatementResult::createSingleStringColumnResult()},
          name{std::move(name)}, type{std::move(type)} {}

    std::string getName() const { return name; };

    const common::LogicalType& getType() const { return type; }

private:
    std::string name;
    common::LogicalType type;
};

} // namespace binder
} // namespace lbug
