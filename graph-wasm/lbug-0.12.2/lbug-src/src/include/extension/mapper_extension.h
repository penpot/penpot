#pragma once

#include "planner/operator/logical_operator.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace extension {

class LBUG_API MapperExtension {
public:
    MapperExtension() {}

    virtual ~MapperExtension() = default;

    virtual std::unique_ptr<processor::PhysicalOperator> map(
        const planner::LogicalOperator* logicalOperator, main::ClientContext* context,
        uint32_t operatorID) = 0;
};

} // namespace extension
} // namespace lbug
