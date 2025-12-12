#pragma once

#include "common/types/types.h"

namespace lbug {
namespace planner {
class Schema;
} // namespace planner

namespace processor {

struct DataChunkDescriptor {
    bool isSingleState;
    std::vector<common::LogicalType> logicalTypes;

    explicit DataChunkDescriptor(bool isSingleState) : isSingleState{isSingleState} {}
    DataChunkDescriptor(const DataChunkDescriptor& other)
        : isSingleState{other.isSingleState},
          logicalTypes(common::LogicalType::copy(other.logicalTypes)) {}

    inline std::unique_ptr<DataChunkDescriptor> copy() const {
        return std::make_unique<DataChunkDescriptor>(*this);
    }
};

struct LBUG_API ResultSetDescriptor {
    std::vector<std::unique_ptr<DataChunkDescriptor>> dataChunkDescriptors;

    ResultSetDescriptor() = default;
    explicit ResultSetDescriptor(
        std::vector<std::unique_ptr<DataChunkDescriptor>> dataChunkDescriptors)
        : dataChunkDescriptors{std::move(dataChunkDescriptors)} {}
    explicit ResultSetDescriptor(planner::Schema* schema);
    DELETE_BOTH_COPY(ResultSetDescriptor);

    std::unique_ptr<ResultSetDescriptor> copy() const;

    static std::unique_ptr<ResultSetDescriptor> EmptyDescriptor() {
        return std::make_unique<ResultSetDescriptor>();
    }
};

} // namespace processor
} // namespace lbug
