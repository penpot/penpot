#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct TernaryFunctionWrapper {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(A_TYPE& a, B_TYPE& b, C_TYPE& c, RESULT_TYPE& result,
        void* /*aValueVector*/, void* /*resultValueVector*/, void* /*dataPtr*/) {
        OP::operation(a, b, c, result);
    }
};

struct TernaryStringFunctionWrapper {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(A_TYPE& a, B_TYPE& b, C_TYPE& c, RESULT_TYPE& result,
        void* /*aValueVector*/, void* resultValueVector, void* /*dataPtr*/) {
        OP::operation(a, b, c, result, *(common::ValueVector*)resultValueVector);
    }
};

struct TernaryRegexFunctionWrapper {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(A_TYPE& a, B_TYPE& b, C_TYPE& c, RESULT_TYPE& result,
        void* /*aValueVector*/, void* resultValueVector, void* dataPtr) {
        OP::operation(a, b, c, result, *(common::ValueVector*)resultValueVector, dataPtr);
    }
};

struct TernaryListFunctionWrapper {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(A_TYPE& a, B_TYPE& b, C_TYPE& c, RESULT_TYPE& result,
        void* aValueVector, void* resultValueVector, void* /*dataPtr*/) {
        OP::operation(a, b, c, result, *(common::ValueVector*)aValueVector,
            *(common::ValueVector*)resultValueVector);
    }
};

struct TernaryUDFFunctionWrapper {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename OP>
    static inline void operation(A_TYPE& a, B_TYPE& b, C_TYPE& c, RESULT_TYPE& result,
        void* /*aValueVector*/, void* /*resultValueVector*/, void* dataPtr) {
        OP::operation(a, b, c, result, dataPtr);
    }
};

struct TernaryFunctionExecutor {
    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeOnValue(common::ValueVector& a, common::ValueVector& b,
        common::ValueVector& c, common::ValueVector& result, uint64_t aPos, uint64_t bPos,
        uint64_t cPos, uint64_t resPos, void* dataPtr) {
        auto resValues = (RESULT_TYPE*)result.getData();
        OP_WRAPPER::template operation<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC>(
            ((A_TYPE*)a.getData())[aPos], ((B_TYPE*)b.getData())[bPos],
            ((C_TYPE*)c.getData())[cPos], resValues[resPos], (void*)&a, (void*)&result, dataPtr);
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeAllFlat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        auto aPos = (*aSelVector)[0];
        auto bPos = (*bSelVector)[0];
        auto cPos = (*cSelVector)[0];
        auto resPos = (*resultSelVector)[0];
        result.setNull(resPos, a.isNull(aPos) || b.isNull(bPos) || c.isNull(cPos));
        if (!result.isNull(resPos)) {
            executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c, result,
                aPos, bPos, cPos, resPos, dataPtr);
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeFlatFlatUnflat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        auto aPos = (*aSelVector)[0];
        auto bPos = (*bSelVector)[0];
        if (a.isNull(aPos) || b.isNull(bPos)) {
            result.setAllNull();
        } else if (c.hasNoNullsGuarantee()) {
            if (cSelVector->isUnfiltered()) {
                for (auto i = 0u; i < cSelVector->getSelSize(); ++i) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, bPos, i, rPos, dataPtr);
                }
            } else {
                for (auto i = 0u; i < cSelVector->getSelSize(); ++i) {
                    auto pos = (*cSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, bPos, pos, rPos, dataPtr);
                }
            }
        } else {
            if (cSelVector->isUnfiltered()) {
                for (auto i = 0u; i < cSelVector->getSelSize(); ++i) {
                    result.setNull(i, c.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, bPos, i, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < cSelVector->getSelSize(); ++i) {
                    auto pos = (*cSelVector)[i];
                    result.setNull(pos, c.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, bPos, pos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeFlatUnflatUnflat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        [[maybe_unused]] common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(bSelVector == cSelVector);
        auto aPos = (*aSelVector)[0];
        if (a.isNull(aPos)) {
            result.setAllNull();
        } else if (b.hasNoNullsGuarantee() && c.hasNoNullsGuarantee()) {
            if (bSelVector->isUnfiltered()) {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, i, i, i, dataPtr);
                }
            } else {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    auto pos = (*bSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, pos, pos, rPos, dataPtr);
                }
            }
        } else {
            if (bSelVector->isUnfiltered()) {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    result.setNull(i, b.isNull(i) || c.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, i, i, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    auto pos = (*bSelVector)[i];
                    result.setNull(pos, b.isNull(pos) || c.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, pos, pos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeFlatUnflatFlat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        auto aPos = (*aSelVector)[0];
        auto cPos = (*cSelVector)[0];
        if (a.isNull(aPos) || c.isNull(cPos)) {
            result.setAllNull();
        } else if (b.hasNoNullsGuarantee()) {
            if (bSelVector->isUnfiltered()) {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, i, cPos, rPos, dataPtr);
                }
            } else {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    auto pos = (*bSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, aPos, pos, cPos, rPos, dataPtr);
                }
            }
        } else {
            if (bSelVector->isUnfiltered()) {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    result.setNull(i, b.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, i, cPos, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < bSelVector->getSelSize(); ++i) {
                    auto pos = (*bSelVector)[i];
                    result.setNull(pos, b.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, aPos, pos, cPos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeAllUnFlat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, [[maybe_unused]] common::SelectionVector* bSelVector,
        common::ValueVector& c, [[maybe_unused]] common::SelectionVector* cSelVector,
        common::ValueVector& result, common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(aSelVector == bSelVector && bSelVector == cSelVector);
        if (a.hasNoNullsGuarantee() && b.hasNoNullsGuarantee() && c.hasNoNullsGuarantee()) {
            if (aSelVector->isUnfiltered()) {
                for (uint64_t i = 0; i < aSelVector->getSelSize(); i++) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, i, i, i, rPos, dataPtr);
                }
            } else {
                for (uint64_t i = 0; i < aSelVector->getSelSize(); i++) {
                    auto pos = (*aSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, pos, pos, pos, rPos, dataPtr);
                }
            }
        } else {
            if (aSelVector->isUnfiltered()) {
                for (uint64_t i = 0; i < aSelVector->getSelSize(); i++) {
                    result.setNull(i, a.isNull(i) || b.isNull(i) || c.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, i, i, i, rPos, dataPtr);
                    }
                }
            } else {
                for (uint64_t i = 0; i < aSelVector->getSelSize(); i++) {
                    auto pos = (*aSelVector)[i];
                    result.setNull(pos, a.isNull(pos) || b.isNull(pos) || c.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, pos, pos, pos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeUnflatFlatFlat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        auto bPos = (*bSelVector)[0];
        auto cPos = (*cSelVector)[0];
        if (b.isNull(bPos) || c.isNull(cPos)) {
            result.setAllNull();
        } else if (a.hasNoNullsGuarantee()) {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, i, bPos, cPos, rPos, dataPtr);
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*aSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, pos, bPos, cPos, rPos, dataPtr);
                }
            }
        } else {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    result.setNull(i, a.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, i, bPos, cPos, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*aSelVector)[i];
                    result.setNull(pos, a.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, pos, bPos, cPos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeUnflatFlatUnflat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        [[maybe_unused]] common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(aSelVector == cSelVector);
        auto bPos = (*bSelVector)[0];
        if (b.isNull(bPos)) {
            result.setAllNull();
        } else if (a.hasNoNullsGuarantee() && c.hasNoNullsGuarantee()) {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, i, bPos, i, rPos, dataPtr);
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*aSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, pos, bPos, pos, rPos, dataPtr);
                }
            }
        } else {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    result.setNull(i, a.isNull(i) || c.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, i, bPos, i, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*bSelVector)[i];
                    result.setNull(pos, a.isNull(pos) || c.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, pos, bPos, pos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeUnflatUnFlatFlat(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, [[maybe_unused]] common::SelectionVector* bSelVector,
        common::ValueVector& c, common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(aSelVector == bSelVector);
        auto cPos = (*cSelVector)[0];
        if (c.isNull(cPos)) {
            result.setAllNull();
        } else if (a.hasNoNullsGuarantee() && b.hasNoNullsGuarantee()) {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, i, i, cPos, rPos, dataPtr);
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*aSelVector)[i];
                    auto rPos = (*resultSelVector)[i];
                    executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b, c,
                        result, pos, pos, cPos, rPos, dataPtr);
                }
            }
        } else {
            if (aSelVector->isUnfiltered()) {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    result.setNull(i, a.isNull(i) || b.isNull(i));
                    if (!result.isNull(i)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, i, i, cPos, rPos, dataPtr);
                    }
                }
            } else {
                for (auto i = 0u; i < aSelVector->getSelSize(); ++i) {
                    auto pos = (*aSelVector)[i];
                    result.setNull(pos, a.isNull(pos) || b.isNull(pos));
                    if (!result.isNull(pos)) {
                        auto rPos = (*resultSelVector)[i];
                        executeOnValue<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, b,
                            c, result, pos, pos, cPos, rPos, dataPtr);
                    }
                }
            }
        }
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC,
        typename OP_WRAPPER>
    static void executeSwitch(common::ValueVector& a, common::SelectionVector* aSelVector,
        common::ValueVector& b, common::SelectionVector* bSelVector, common::ValueVector& c,
        common::SelectionVector* cSelVector, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        result.resetAuxiliaryBuffer();
        if (a.state->isFlat() && b.state->isFlat() && c.state->isFlat()) {
            executeAllFlat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, aSelVector, b,
                bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (a.state->isFlat() && b.state->isFlat() && !c.state->isFlat()) {
            executeFlatFlatUnflat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (a.state->isFlat() && !b.state->isFlat() && !c.state->isFlat()) {
            executeFlatUnflatUnflat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (a.state->isFlat() && !b.state->isFlat() && c.state->isFlat()) {
            executeFlatUnflatFlat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (!a.state->isFlat() && !b.state->isFlat() && !c.state->isFlat()) {
            executeAllUnFlat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a, aSelVector,
                b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (!a.state->isFlat() && !b.state->isFlat() && c.state->isFlat()) {
            executeUnflatUnFlatFlat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (!a.state->isFlat() && b.state->isFlat() && c.state->isFlat()) {
            executeUnflatFlatFlat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else if (!a.state->isFlat() && b.state->isFlat() && !c.state->isFlat()) {
            executeUnflatFlatUnflat<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC, OP_WRAPPER>(a,
                aSelVector, b, bSelVector, c, cSelVector, result, resultSelVector, dataPtr);
        } else {
            KU_ASSERT(false);
        }
    }
};

} // namespace function
} // namespace lbug
