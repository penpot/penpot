#pragma once

#include "logical_simple.h"

namespace lbug {
namespace planner {

class LogicalImportDatabase : public LogicalSimple {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::IMPORT_DATABASE;

public:
    LogicalImportDatabase(std::string query, std::string indexQuery)
        : LogicalSimple{type_}, query{std::move(query)}, indexQuery{std::move(indexQuery)} {}

    std::string getQuery() const { return query; }

    std::string getIndexQuery() const { return indexQuery; }

    std::string getExpressionsForPrinting() const override { return std::string{}; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalImportDatabase>(query, indexQuery);
    }

private:
    // see comment in BoundImportDatabase
    std::string query;
    std::string indexQuery;
};

} // namespace planner
} // namespace lbug
