#pragma once

#include <cstdint>

namespace lbug {
namespace function {

/**
 * The boolean operators (AND, OR, XOR, NOT) works a little differently from other operators. While
 * other operators can operate on only non null operands, boolean operators can operate even with
 * null operands in certain cases, for instance, Null OR True = True. Hence, the result value of
 * the boolean operator can be True, False or Null. To accommodate for this, the dataType of
 * result is uint8_t (that can have more than 2 values) rather than bool. In case, the result is
 * computed to be Null based on the operands, we set result = NULL_BOOL, which should rightly be
 * interpreted by operator executors as NULL and not as True.
 * */

/**
 * IMPORTANT: Not to be used outside the context of boolean operators.
 * */
const uint8_t NULL_BOOL = 2;

/**
 * AND operator Truth table:
 *
 *    left      isLeftNull       right       isRightNull        result
 *   ------    ------------     -------     -------------      --------
 *     T            F              T              F               1
 *     T            F              F              F               0
 *     F            F              T              F               0
 *     F            F              F              F               0
 *     -            T              T              F               2
 *     -            T              F              F               0
 *     T            F              -              T               2
 *     F            F              -              T               0
 *     -            T              -              T               2
 * */
struct And {
    static inline void operation(bool left, bool right, uint8_t& result, bool isLeftNull,
        bool isRightNull) {
        if ((!left && !isLeftNull) || (!right && !isRightNull)) {
            result = false;
        } else if (isLeftNull || isRightNull) {
            result = NULL_BOOL;
        } else {
            result = true;
        }
    }
};

/**
 * OR operator Truth table:
 *
 *    left      isLeftNull       right       isRightNull        result
 *   ------    ------------     -------     -------------      --------
 *     T            F              T              F               1
 *     T            F              F              F               1
 *     F            F              T              F               1
 *     F            F              F              F               0
 *     -            T              T              F               1
 *     -            T              F              F               2
 *     T            F              -              T               1
 *     F            F              -              T               2
 *     -            T              -              T               2
 * */
struct Or {
    static inline void operation(bool left, bool right, uint8_t& result, bool isLeftNull,
        bool isRightNull) {
        if ((left && !isLeftNull) || (right && !isRightNull)) {
            result = true;
        } else if (isLeftNull || isRightNull) {
            result = NULL_BOOL;
        } else {
            result = false;
        }
    }
};

/**
 * XOR operator Truth table:
 *
 *    left      isLeftNull       right       isRightNull        result
 *   ------    ------------     -------     -------------      --------
 *     T            F              T              F               0
 *     T            F              F              F               1
 *     F            F              T              F               1
 *     F            F              F              F               0
 *     -            T              T              F               2
 *     -            T              F              F               2
 *     T            F              -              T               2
 *     F            F              -              T               2
 *     -            T              -              T               2
 * */
struct Xor {
    static inline void operation(bool left, bool right, uint8_t& result, bool isLeftNull,
        bool isRightNull) {
        if (isLeftNull || isRightNull) {
            result = NULL_BOOL;
        } else {
            result = left ^ right;
        }
    }
};

/**
 * NOT operator Truth table:
 *
 *    operand         isNull        right
 *   ---------    ------------     -------
 *       T            F              0
 *       F            F              1
 *       -            T              2
 * */
struct Not {
    static inline void operation(bool operand, bool isNull, uint8_t& result) {
        result = isNull ? NULL_BOOL : !operand;
    }
};

} // namespace function
} // namespace lbug
