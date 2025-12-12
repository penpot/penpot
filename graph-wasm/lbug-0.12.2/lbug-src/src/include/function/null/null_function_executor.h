#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct NullOperationExecutor {

    template<typename FUNC>
    static void execute(common::ValueVector& operand, common::SelectionVector& operandSelVector,
        common::ValueVector& result) {
        KU_ASSERT(result.dataType.getLogicalTypeID() == common::LogicalTypeID::BOOL);
        auto resultValues = (uint8_t*)result.getData();
        if (operand.state->isFlat()) {
            auto pos = operandSelVector[0];
            auto resultPos = result.state->getSelVector()[0];
            FUNC::operation(operand.getValue<uint8_t>(pos), (bool)operand.isNull(pos),
                resultValues[resultPos]);
        } else {
            if (operandSelVector.isUnfiltered()) {
                for (auto i = 0u; i < operandSelVector.getSelSize(); i++) {
                    FUNC::operation(operand.getValue<uint8_t>(i), (bool)operand.isNull(i),
                        resultValues[i]);
                }
            } else {
                for (auto i = 0u; i < operandSelVector.getSelSize(); i++) {
                    auto pos = operandSelVector[i];
                    FUNC::operation(operand.getValue<uint8_t>(pos), (bool)operand.isNull(pos),
                        resultValues[pos]);
                }
            }
        }
    }

    template<typename FUNC>
    static bool select(common::ValueVector& operand, common::SelectionVector& selVector,
        void* /*dataPtr*/) {
        auto& operandSelVector = operand.state->getSelVector();
        if (operand.state->isFlat()) {
            auto pos = operandSelVector[0];
            uint8_t resultValue = 0;
            FUNC::operation(operand.getValue<uint8_t>(pos), operand.isNull(pos), resultValue);
            return resultValue == true;
        } else {
            uint64_t numSelectedValues = 0;
            auto buffer = selVector.getMutableBuffer();
            for (auto i = 0ul; i < operandSelVector.getSelSize(); i++) {
                auto pos = operandSelVector[i];
                selectOnValue<FUNC>(operand, pos, numSelectedValues, buffer);
            }
            selVector.setSelSize(numSelectedValues);
            return numSelectedValues > 0;
        }
    }

    template<typename FUNC>
    static void selectOnValue(common::ValueVector& operand, uint64_t operandPos,
        uint64_t& numSelectedValues, std::span<common::sel_t> selectedPositionsBuffer) {
        uint8_t resultValue = 0;
        FUNC::operation(operand.getValue<uint8_t>(operandPos), operand.isNull(operandPos),
            resultValue);
        selectedPositionsBuffer[numSelectedValues] = operandPos;
        numSelectedValues += resultValue == true;
    }
};

} // namespace function
} // namespace lbug
