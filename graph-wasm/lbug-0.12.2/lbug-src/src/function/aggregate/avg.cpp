#include "function/aggregate/avg.h"

namespace lbug {
namespace function {

using namespace lbug::common;

function_set AggregateAvgFunction::getFunctionSet() {
    function_set result;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        AggregateFunctionUtils::appendSumOrAvgFuncs<AvgFunction>(name, typeID, result);
    }
    return result;
}

} // namespace function
} // namespace lbug
