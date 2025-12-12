#include "expression_evaluator/reference_evaluator.h"

using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace evaluator {

inline static bool isTrue(ValueVector& vector, uint64_t pos) {
    KU_ASSERT(vector.dataType.getLogicalTypeID() == LogicalTypeID::BOOL);
    return !vector.isNull(pos) && vector.getValue<bool>(pos);
}

bool ReferenceExpressionEvaluator::selectInternal(SelectionVector& selVector) {
    uint64_t numSelectedValues = 0;
    auto selectedBuffer = resultVector->state->getSelVectorUnsafe().getMutableBuffer();
    if (resultVector->state->getSelVector().isUnfiltered()) {
        for (auto i = 0u; i < resultVector->state->getSelVector().getSelSize(); i++) {
            selectedBuffer[numSelectedValues] = i;
            numSelectedValues += isTrue(*resultVector, i);
        }
    } else {
        for (auto i = 0u; i < resultVector->state->getSelVector().getSelSize(); i++) {
            auto pos = resultVector->state->getSelVector()[i];
            selectedBuffer[numSelectedValues] = pos;
            numSelectedValues += isTrue(*resultVector, pos);
        }
    }
    selVector.setSelSize(numSelectedValues);
    return numSelectedValues > 0;
}

} // namespace evaluator
} // namespace lbug
