#pragma once

#include "common/vector/value_vector.h"
#include "function/cast/cast_function_bind_data.h"

namespace lbug {
namespace function {

/**
 * Unary operator assumes operation with null returns null. This does NOT applies to IS_NULL and
 * IS_NOT_NULL operation.
 */

struct UnaryFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static inline void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* /*dataPtr*/) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_.getValue<RESULT_TYPE>(resultPos));
    }
};

struct UnarySequenceFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static inline void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t /* resultPos */, void* dataPtr) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos), resultVector_, dataPtr);
    }
};

struct UnaryStringFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* /*dataPtr*/) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_.getValue<RESULT_TYPE>(resultPos), resultVector_);
    }
};

struct UnaryCastStringFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* dataPtr) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto resultVector_ = (common::ValueVector*)resultVector;
        // TODO(Ziyi): the reinterpret_cast is not safe since we don't always pass
        // CastFunctionBindData
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_->getValue<RESULT_TYPE>(resultPos), resultVector_, inputPos,
            &reinterpret_cast<CastFunctionBindData*>(dataPtr)->option);
    }
};

struct UnaryNestedTypeFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static inline void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* /*dataPtr*/) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_.getValue<RESULT_TYPE>(resultPos), inputVector_, resultVector_);
    }
};

struct SetSeedFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static inline void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* dataPtr) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        resultVector_.setNull(resultPos, true /* isNull */);
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos), dataPtr);
    }
};

struct UnaryCastFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* /*dataPtr*/) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_.getValue<RESULT_TYPE>(resultPos), inputVector_, resultVector_);
    }
};

struct UnaryCastUnionFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* dataPtr) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_, resultVector_, inputPos, resultPos, dataPtr);
    }
};

struct UnaryUDFFunctionWrapper {
    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static inline void operation(void* inputVector, uint64_t inputPos, void* resultVector,
        uint64_t resultPos, void* dataPtr) {
        auto& inputVector_ = *(common::ValueVector*)inputVector;
        auto& resultVector_ = *(common::ValueVector*)resultVector;
        FUNC::operation(inputVector_.getValue<OPERAND_TYPE>(inputPos),
            resultVector_.getValue<RESULT_TYPE>(resultPos), dataPtr);
    }
};

struct UnaryFunctionExecutor {

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC, typename OP_WRAPPER>
    static void executeOnValue(common::ValueVector& inputVector, uint64_t inputPos,
        common::ValueVector& resultVector, uint64_t resultPos, void* dataPtr) {
        OP_WRAPPER::template operation<OPERAND_TYPE, RESULT_TYPE, FUNC>((void*)&inputVector,
            inputPos, (void*)&resultVector, resultPos, dataPtr);
    }

    static std::pair<common::sel_t, common::sel_t> getSelectedPos(common::idx_t selIdx,
        common::SelectionVector* operandSelVector, common::SelectionVector* resultSelVector,
        bool operandIsUnfiltered, bool resultIsUnfiltered) {
        common::sel_t operandPos = operandIsUnfiltered ? selIdx : (*operandSelVector)[selIdx];
        common::sel_t resultPos = resultIsUnfiltered ? selIdx : (*resultSelVector)[selIdx];
        return {operandPos, resultPos};
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC, typename OP_WRAPPER>
    static void executeOnSelectedValues(common::ValueVector& operand,
        common::SelectionVector* operandSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        const bool noNullsGuaranteed = operand.hasNoNullsGuarantee();
        if (noNullsGuaranteed) {
            result.setAllNonNull();
        }

        const bool operandIsUnfiltered = operandSelVector->isUnfiltered();
        const bool resultIsUnfiltered = resultSelVector->isUnfiltered();

        for (auto i = 0u; i < operandSelVector->getSelSize(); i++) {
            const auto [operandPos, resultPos] = getSelectedPos(i, operandSelVector,
                resultSelVector, operandIsUnfiltered, resultIsUnfiltered);
            if (noNullsGuaranteed) {
                executeOnValue<OPERAND_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(operand, operandPos,
                    result, resultPos, dataPtr);
            } else {
                result.setNull(resultPos, operand.isNull(operandPos));
                if (!result.isNull(resultPos)) {
                    executeOnValue<OPERAND_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(operand, operandPos,
                        result, resultPos, dataPtr);
                }
            }
        }
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC, typename OP_WRAPPER>
    static void executeSwitch(common::ValueVector& operand,
        common::SelectionVector* operandSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        result.resetAuxiliaryBuffer();
        if (operand.state->isFlat()) {
            auto inputPos = (*operandSelVector)[0];
            auto resultPos = (*resultSelVector)[0];
            result.setNull(resultPos, operand.isNull(inputPos));
            if (!result.isNull(resultPos)) {
                executeOnValue<OPERAND_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(operand, inputPos,
                    result, resultPos, dataPtr);
            }
        } else {
            executeOnSelectedValues<OPERAND_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(operand,
                operandSelVector, result, resultSelVector, dataPtr);
        }
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void execute(common::ValueVector& operand, common::SelectionVector* operandSelVector,
        common::ValueVector& result, common::SelectionVector* resultSelVector) {
        executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC, UnaryFunctionWrapper>(operand,
            operandSelVector, result, resultSelVector, nullptr /* dataPtr */);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void executeSequence(common::ValueVector& operand,
        common::SelectionVector* operandSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        result.resetAuxiliaryBuffer();
        auto inputPos = (*operandSelVector)[0];
        auto resultPos = (*resultSelVector)[0];
        executeOnValue<OPERAND_TYPE, RESULT_TYPE, FUNC, UnarySequenceFunctionWrapper>(operand,
            inputPos, result, resultPos, dataPtr);
    }
};

} // namespace function
} // namespace lbug
