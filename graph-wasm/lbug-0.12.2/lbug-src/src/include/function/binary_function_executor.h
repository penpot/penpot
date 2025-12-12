#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

/**
 * Binary operator assumes function with null returns null. This does NOT applies to binary boolean
 * operations (e.g. AND, OR, XOR).
 */

struct BinaryFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* /*leftValueVector*/, common::ValueVector* /*rightValueVector*/,
        common::ValueVector* /*resultValueVector*/, uint64_t /*resultPos*/, void* /*dataPtr*/) {
        OP::operation(left, right, result);
    }
};

struct BinaryListStructFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
        common::ValueVector* resultValueVector, uint64_t /*resultPos*/, void* /*dataPtr*/) {
        OP::operation(left, right, result, *leftValueVector, *rightValueVector, *resultValueVector);
    }
};

struct BinaryMapCreationFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
        common::ValueVector* resultValueVector, uint64_t /*resultPos*/, void* dataPtr) {
        OP::operation(left, right, result, *leftValueVector, *rightValueVector, *resultValueVector,
            dataPtr);
    }
};

struct BinaryListExtractFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
        common::ValueVector* resultValueVector, uint64_t resultPos, void* /*dataPtr*/) {
        OP::operation(left, right, result, *leftValueVector, *rightValueVector, *resultValueVector,
            resultPos);
    }
};

struct BinaryStringFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* /*leftValueVector*/, common::ValueVector* /*rightValueVector*/,
        common::ValueVector* resultValueVector, uint64_t /*resultPos*/, void* /*dataPtr*/) {
        OP::operation(left, right, result, *resultValueVector);
    }
};

struct BinaryComparisonFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
        common::ValueVector* /*resultValueVector*/, uint64_t /*resultPos*/, void* /*dataPtr*/) {
        OP::operation(left, right, result, leftValueVector, rightValueVector);
    }
};

struct BinaryUDFFunctionWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, RESULT_TYPE& result,
        common::ValueVector* /*leftValueVector*/, common::ValueVector* /*rightValueVector*/,
        common::ValueVector* /*resultValueVector*/, uint64_t /*resultPos*/, void* dataPtr) {
        OP::operation(left, right, result, dataPtr);
    }
};

struct BinarySelectWithBindDataWrapper {
    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename OP>
    static void operation(LEFT_TYPE& left, RIGHT_TYPE& right, uint8_t& result,
        common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
        void* dataPtr) {
        OP::operation(left, right, result, *leftValueVector, *rightValueVector, *leftValueVector,
            dataPtr);
    }
};

struct BinaryFunctionExecutor {

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static inline void executeOnValue(common::ValueVector& left, common::ValueVector& right,
        common::ValueVector& resultValueVector, uint64_t lPos, uint64_t rPos, uint64_t resPos,
        void* dataPtr) {
        OP_WRAPPER::template operation<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC>(
            ((LEFT_TYPE*)left.getData())[lPos], ((RIGHT_TYPE*)right.getData())[rPos],
            ((RESULT_TYPE*)resultValueVector.getData())[resPos], &left, &right, &resultValueVector,
            resPos, dataPtr);
    }

    static inline std::tuple<common::sel_t, common::sel_t, common::sel_t> getSelectedPositions(
        common::SelectionVector* leftSelVector, common::SelectionVector* rightSelVector,
        common::SelectionVector* resultSelVector, common::sel_t selPos, bool leftFlat,
        bool rightFlat) {
        common::sel_t lPos = (*leftSelVector)[leftFlat ? 0 : selPos];
        common::sel_t rPos = (*rightSelVector)[rightFlat ? 0 : selPos];
        common::sel_t resPos = (*resultSelVector)[leftFlat && rightFlat ? 0 : selPos];
        return {lPos, rPos, resPos};
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeOnSelectedValues(common::ValueVector& left,
        common::SelectionVector* leftSelVector, common::ValueVector& right,
        common::SelectionVector* rightSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        const bool leftFlat = left.state->isFlat();
        const bool rightFlat = right.state->isFlat();

        const bool allNullsGuaranteed = (rightFlat && right.isNull((*rightSelVector)[0])) ||
                                        (leftFlat && left.isNull((*leftSelVector)[0]));
        if (allNullsGuaranteed) {
            result.setAllNull();
        } else {
            const bool noNullsGuaranteed = (leftFlat || left.hasNoNullsGuarantee()) &&
                                           (rightFlat || right.hasNoNullsGuarantee());
            if (noNullsGuaranteed) {
                result.setAllNonNull();
            }

            const auto numSelectedValues =
                leftFlat ? rightSelVector->getSelSize() : leftSelVector->getSelSize();
            for (common::sel_t selPos = 0; selPos < numSelectedValues; ++selPos) {
                auto [lPos, rPos, resPos] = getSelectedPositions(leftSelVector, rightSelVector,
                    resultSelVector, selPos, leftFlat, rightFlat);
                if (noNullsGuaranteed) {
                    executeOnValue<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(left,
                        right, result, lPos, rPos, resPos, dataPtr);
                } else {
                    result.setNull(resPos, left.isNull(lPos) || right.isNull(rPos));
                    if (!result.isNull(resPos)) {
                        executeOnValue<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(left,
                            right, result, lPos, rPos, resPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeSwitch(common::ValueVector& left, common::SelectionVector* leftSelVector,
        common::ValueVector& right, common::SelectionVector* rightSelVector,
        common::ValueVector& result, common::SelectionVector* resultSelVector, void* dataPtr) {
        result.resetAuxiliaryBuffer();
        executeOnSelectedValues<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(left,
            leftSelVector, right, rightSelVector, result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void execute(common::ValueVector& left, common::SelectionVector* leftSelVector,
        common::ValueVector& right, common::SelectionVector* rightSelVector,
        common::ValueVector& result, common::SelectionVector* resultSelVector) {
        executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC, BinaryFunctionWrapper>(left,
            leftSelVector, right, rightSelVector, result, resultSelVector, nullptr /* dataPtr */);
    }

    struct BinarySelectWrapper {
        template<typename LEFT_TYPE, typename RIGHT_TYPE, typename OP>
        static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, uint8_t& result,
            common::ValueVector* /*leftValueVector*/, common::ValueVector* /*rightValueVector*/,
            void* /*dataPtr*/) {
            OP::operation(left, right, result);
        }
    };

    struct BinaryComparisonSelectWrapper {
        template<typename LEFT_TYPE, typename RIGHT_TYPE, typename OP>
        static inline void operation(LEFT_TYPE& left, RIGHT_TYPE& right, uint8_t& result,
            common::ValueVector* leftValueVector, common::ValueVector* rightValueVector,
            void* /*dataPtr*/) {
            OP::operation(left, right, result, leftValueVector, rightValueVector);
        }
    };

    template<class LEFT_TYPE, class RIGHT_TYPE, class FUNC, typename SELECT_WRAPPER>
    static void selectOnValue(common::ValueVector& left, common::ValueVector& right, uint64_t lPos,
        uint64_t rPos, uint64_t resPos, uint64_t& numSelectedValues,
        std::span<common::sel_t> selectedPositionsBuffer, void* dataPtr) {
        uint8_t resultValue = 0;
        SELECT_WRAPPER::template operation<LEFT_TYPE, RIGHT_TYPE, FUNC>(
            ((LEFT_TYPE*)left.getData())[lPos], ((RIGHT_TYPE*)right.getData())[rPos], resultValue,
            &left, &right, dataPtr);
        selectedPositionsBuffer[numSelectedValues] = resPos;
        numSelectedValues += (resultValue == true);
    }

    template<class LEFT_TYPE, class RIGHT_TYPE, class FUNC, typename SELECT_WRAPPER>
    static uint64_t selectBothFlat(common::ValueVector& left, common::ValueVector& right,
        void* dataPtr) {
        auto lPos = left.state->getSelVector()[0];
        auto rPos = right.state->getSelVector()[0];
        uint8_t resultValue = 0;
        if (!left.isNull(lPos) && !right.isNull(rPos)) {
            SELECT_WRAPPER::template operation<LEFT_TYPE, RIGHT_TYPE, FUNC>(
                ((LEFT_TYPE*)left.getData())[lPos], ((RIGHT_TYPE*)right.getData())[rPos],
                resultValue, &left, &right, dataPtr);
        }
        return resultValue == true;
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename FUNC, typename SELECT_WRAPPER>
    static bool selectFlatUnFlat(common::ValueVector& left, common::ValueVector& right,
        common::SelectionVector& selVector, void* dataPtr) {
        auto lPos = left.state->getSelVector()[0];
        uint64_t numSelectedValues = 0;
        auto selectedPositionsBuffer = selVector.getMutableBuffer();
        auto& rightSelVector = right.state->getSelVector();
        if (left.isNull(lPos)) {
            return numSelectedValues;
        } else if (right.hasNoNullsGuarantee()) {
            rightSelVector.forEach([&](auto i) {
                selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, lPos, i, i,
                    numSelectedValues, selectedPositionsBuffer, dataPtr);
            });
        } else {
            rightSelVector.forEach([&](auto i) {
                if (!right.isNull(i)) {
                    selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, lPos, i,
                        i, numSelectedValues, selectedPositionsBuffer, dataPtr);
                }
            });
        }
        selVector.setSelSize(numSelectedValues);
        return numSelectedValues > 0;
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename FUNC, typename SELECT_WRAPPER>
    static bool selectUnFlatFlat(common::ValueVector& left, common::ValueVector& right,
        common::SelectionVector& selVector, void* dataPtr) {
        auto rPos = right.state->getSelVector()[0];
        uint64_t numSelectedValues = 0;
        auto selectedPositionsBuffer = selVector.getMutableBuffer();
        auto& leftSelVector = left.state->getSelVector();
        if (right.isNull(rPos)) {
            return numSelectedValues;
        } else if (left.hasNoNullsGuarantee()) {
            leftSelVector.forEach([&](auto i) {
                selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, i, rPos, i,
                    numSelectedValues, selectedPositionsBuffer, dataPtr);
            });
        } else {
            leftSelVector.forEach([&](auto i) {
                if (!left.isNull(i)) {
                    selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, i, rPos,
                        i, numSelectedValues, selectedPositionsBuffer, dataPtr);
                }
            });
        }
        selVector.setSelSize(numSelectedValues);
        return numSelectedValues > 0;
    }

    // Right, left, and result vectors share the same selectedPositions.
    template<class LEFT_TYPE, class RIGHT_TYPE, class FUNC, typename SELECT_WRAPPER>
    static bool selectBothUnFlat(common::ValueVector& left, common::ValueVector& right,
        common::SelectionVector& selVector, void* dataPtr) {
        uint64_t numSelectedValues = 0;
        auto selectedPositionsBuffer = selVector.getMutableBuffer();
        auto& leftSelVector = left.state->getSelVector();
        if (left.hasNoNullsGuarantee() && right.hasNoNullsGuarantee()) {
            leftSelVector.forEach([&](auto i) {
                selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, i, i, i,
                    numSelectedValues, selectedPositionsBuffer, dataPtr);
            });
        } else {
            leftSelVector.forEach([&](auto i) {
                auto isNull = left.isNull(i) || right.isNull(i);
                if (!isNull) {
                    selectOnValue<LEFT_TYPE, RIGHT_TYPE, FUNC, SELECT_WRAPPER>(left, right, i, i, i,
                        numSelectedValues, selectedPositionsBuffer, dataPtr);
                }
            });
        }
        selVector.setSelSize(numSelectedValues);
        return numSelectedValues > 0;
    }

    // BOOLEAN (AND, OR, XOR)
    template<class LEFT_TYPE, class RIGHT_TYPE, class FUNC,
        typename OP_WRAPPER = BinarySelectWrapper>
    static bool select(common::ValueVector& left, common::ValueVector& right,
        common::SelectionVector& selVector, void* dataPtr) {
        if (left.state->isFlat() && right.state->isFlat()) {
            return selectBothFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, OP_WRAPPER>(left, right, dataPtr);
        } else if (left.state->isFlat() && !right.state->isFlat()) {
            return selectFlatUnFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, OP_WRAPPER>(left, right, selVector,
                dataPtr);
        } else if (!left.state->isFlat() && right.state->isFlat()) {
            return selectUnFlatFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, OP_WRAPPER>(left, right, selVector,
                dataPtr);
        } else {
            return selectBothUnFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, OP_WRAPPER>(left, right, selVector,
                dataPtr);
        }
    }

    // COMPARISON (GT, GTE, LT, LTE, EQ, NEQ)
    template<class LEFT_TYPE, class RIGHT_TYPE, class FUNC>
    static bool selectComparison(common::ValueVector& left, common::ValueVector& right,
        common::SelectionVector& selVector, void* dataPtr) {
        if (left.state->isFlat() && right.state->isFlat()) {
            return selectBothFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, BinaryComparisonSelectWrapper>(left,
                right, dataPtr);
        } else if (left.state->isFlat() && !right.state->isFlat()) {
            return selectFlatUnFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, BinaryComparisonSelectWrapper>(
                left, right, selVector, dataPtr);
        } else if (!left.state->isFlat() && right.state->isFlat()) {
            return selectUnFlatFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, BinaryComparisonSelectWrapper>(
                left, right, selVector, dataPtr);
        } else {
            return selectBothUnFlat<LEFT_TYPE, RIGHT_TYPE, FUNC, BinaryComparisonSelectWrapper>(
                left, right, selVector, dataPtr);
        }
    }
};

} // namespace function
} // namespace lbug
