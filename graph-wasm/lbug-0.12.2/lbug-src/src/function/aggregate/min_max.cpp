#include "function/aggregate/min_max.h"

#include "common/type_utils.h"
#include "function/comparison/comparison_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

template<typename FUNC>
static void getMinMaxFunction(std::string name, function_set& set) {
    std::unique_ptr<AggregateFunction> func;
    for (auto& type : LogicalTypeUtils::getAllValidComparableLogicalTypes()) {
        auto inputTypes = std::vector<common::LogicalTypeID>{type};
        for (auto isDistinct : std::vector<bool>{true, false}) {
            common::TypeUtils::visit(
                LogicalType::getPhysicalType(type),
                [&]<ComparableTypes T>(T) {
                    func = std::make_unique<AggregateFunction>(name, inputTypes, type,
                        MinMaxFunction<T>::initialize, MinMaxFunction<T>::template updateAll<FUNC>,
                        MinMaxFunction<T>::template updatePos<FUNC>,
                        MinMaxFunction<T>::template combine<FUNC>, MinMaxFunction<T>::finalize,
                        isDistinct);
                },
                [](auto) { KU_UNREACHABLE; });
            set.push_back(std::move(func));
        }
    }
}

function_set AggregateMinFunction::getFunctionSet() {
    function_set result;
    getMinMaxFunction<LessThan>(AggregateMinFunction::name, result);
    return result;
}

function_set AggregateMaxFunction::getFunctionSet() {
    function_set result;
    getMinMaxFunction<GreaterThan>(AggregateMaxFunction::name, result);
    return result;
}

} // namespace function
} // namespace lbug
