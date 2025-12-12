#include "expression_evaluator/literal_evaluator.h"

#include "common/types/value/value.h"

using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::main;

namespace lbug {
namespace evaluator {

void LiteralExpressionEvaluator::evaluate() {}

void LiteralExpressionEvaluator::evaluate(sel_t count) {
    unFlatState->getSelVectorUnsafe().setSelSize(count);
    resultVector->setState(unFlatState);
    for (auto i = 1ul; i < count; i++) {
        resultVector->copyFromVectorData(i, resultVector.get(), 0);
    }
}

bool LiteralExpressionEvaluator::selectInternal(SelectionVector&) {
    KU_ASSERT(resultVector->dataType.getLogicalTypeID() == LogicalTypeID::BOOL);
    auto pos = resultVector->state->getSelVector()[0];
    KU_ASSERT(pos == 0u);
    return resultVector->getValue<bool>(pos) && (!resultVector->isNull(pos));
}

void LiteralExpressionEvaluator::resolveResultVector(const processor::ResultSet& /*resultSet*/,
    MemoryManager* memoryManager) {
    resultVector = std::make_shared<ValueVector>(value.getDataType().copy(), memoryManager);
    flatState = DataChunkState::getSingleValueDataChunkState();
    unFlatState = std::make_shared<DataChunkState>();
    resultVector->setState(flatState);
    if (value.isNull()) {
        resultVector->setNull(0 /* pos */, true);
    } else {
        resultVector->copyFromValue(resultVector->state->getSelVector()[0], value);
    }
}

} // namespace evaluator
} // namespace lbug
