#include "processor/result/result_set_descriptor.h"

#include "planner/operator/schema.h"

namespace lbug {
namespace processor {

ResultSetDescriptor::ResultSetDescriptor(planner::Schema* schema) {
    for (auto i = 0u; i < schema->getNumGroups(); ++i) {
        auto group = schema->getGroup(i);
        auto dataChunkDescriptor = std::make_unique<DataChunkDescriptor>(group->isSingleState());
        for (auto& expression : group->getExpressions()) {
            dataChunkDescriptor->logicalTypes.push_back(expression->getDataType().copy());
        }
        dataChunkDescriptors.push_back(std::move(dataChunkDescriptor));
    }
}

std::unique_ptr<ResultSetDescriptor> ResultSetDescriptor::copy() const {
    std::vector<std::unique_ptr<DataChunkDescriptor>> dataChunkDescriptorsCopy;
    dataChunkDescriptorsCopy.reserve(dataChunkDescriptors.size());
    for (auto& dataChunkDescriptor : dataChunkDescriptors) {
        dataChunkDescriptorsCopy.push_back(
            std::make_unique<DataChunkDescriptor>(*dataChunkDescriptor));
    }
    return std::make_unique<ResultSetDescriptor>(std::move(dataChunkDescriptorsCopy));
}

} // namespace processor
} // namespace lbug
