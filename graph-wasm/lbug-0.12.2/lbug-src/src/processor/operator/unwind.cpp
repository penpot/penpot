#include "processor/operator/unwind.h"

#include "binder/expression/expression.h" // IWYU pragma: keep
#include "common/system_config.h"
#include "processor/execution_context.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::string UnwindPrintInfo::toString() const {
    std::string result = "Unwind: ";
    result += inExpression->toString();
    result += ", As: ";
    result += outExpression->toString();
    return result;
}

void Unwind::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    expressionEvaluator->init(*resultSet, context->clientContext);
    outValueVector = resultSet->getValueVector(outDataPos);
    if (idPos.isValid()) {
        idVector = resultSet->getValueVector(idPos).get();
    }
}

bool Unwind::hasMoreToRead() const {
    return listEntry.offset != INVALID_OFFSET && listEntry.size > startIndex;
}

void Unwind::copyTuplesToOutVector(uint64_t startPos, uint64_t endPos) const {
    auto listDataVector = ListVector::getDataVector(expressionEvaluator->resultVector.get());
    auto listPos = listEntry.offset + startPos;
    for (auto i = 0u; i < endPos - startPos; i++) {
        outValueVector->copyFromVectorData(i, listDataVector, listPos++);
    }
    if (idVector != nullptr) {
        KU_ASSERT(listDataVector->dataType.getLogicalTypeID() == common::LogicalTypeID::NODE);
        auto idFieldVector = StructVector::getFieldVector(listDataVector, 0);
        listPos = listEntry.offset + startPos;
        for (auto i = 0u; i < endPos - startPos; i++) {
            idVector->copyFromVectorData(i, idFieldVector.get(), listPos++);
        }
    }
}

bool Unwind::getNextTuplesInternal(ExecutionContext* context) {
    if (hasMoreToRead()) {
        auto totalElementsCopy =
            std::min(DEFAULT_VECTOR_CAPACITY, (uint64_t)listEntry.size - startIndex);
        copyTuplesToOutVector(startIndex, (totalElementsCopy + startIndex));
        startIndex += totalElementsCopy;
        outValueVector->state->initOriginalAndSelectedSize(totalElementsCopy);
        return true;
    }
    do {
        if (!children[0]->getNextTuple(context)) {
            return false;
        }
        expressionEvaluator->evaluate();
        auto pos = expressionEvaluator->resultVector->state->getSelVector()[0];
        if (expressionEvaluator->resultVector->isNull(pos)) {
            outValueVector->state->getSelVectorUnsafe().setSelSize(0);
            continue;
        }
        listEntry = expressionEvaluator->resultVector->getValue<list_entry_t>(pos);
        startIndex = 0;
        auto totalElementsCopy = std::min(DEFAULT_VECTOR_CAPACITY, (uint64_t)listEntry.size);
        copyTuplesToOutVector(0, totalElementsCopy);
        startIndex += totalElementsCopy;
        outValueVector->state->initOriginalAndSelectedSize(startIndex);
    } while (outValueVector->state->getSelVector().getSelSize() == 0);
    metrics->numOutputTuple.increase(outValueVector->state->getSelVector().getSelSize());
    return true;
}

} // namespace processor
} // namespace lbug
