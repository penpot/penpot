#pragma once

#include "binder/bound_statement.h"
#include "planner/planner.h"

namespace lbug {
namespace extension {

class PlannerExtension {

public:
    PlannerExtension() {}

    virtual ~PlannerExtension() = default;

    virtual std::shared_ptr<planner::LogicalOperator> plan(
        const binder::BoundStatement& boundStatement) = 0;
};

} // namespace extension
} // namespace lbug
