#pragma once

#include "logical_simple.h"

namespace lbug {
namespace planner {

class LogicalUseDatabase final : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::USE_DATABASE;

public:
    explicit LogicalUseDatabase(std::string dbName)
        : LogicalSimple{type_}, dbName{std::move(dbName)} {}

    std::string getDBName() const { return dbName; }

    std::string getExpressionsForPrinting() const override { return dbName; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalUseDatabase>(dbName);
    }

private:
    std::string dbName;
};

} // namespace planner
} // namespace lbug
