#include "processor/operator/aggregate/base_aggregate_scan.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace processor {

void BaseAggregateScan::initLocalStateInternal(ResultSet* resultSet,
    ExecutionContext* /*context*/) {
    for (auto& dataPos : scanInfo.aggregatesPos) {
        auto valueVector = resultSet->getValueVector(dataPos);
        aggregateVectors.push_back(valueVector);
    }
}

} // namespace processor
} // namespace lbug
