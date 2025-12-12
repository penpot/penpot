#pragma once

#include <typeinfo>

#include "common/assert.h"

namespace lbug {
namespace common {

template<typename TO, typename FROM>
TO ku_dynamic_cast(FROM* old) {
#if defined(LBUG_RUNTIME_CHECKS) || !defined(NDEBUG)
    static_assert(std::is_pointer<TO>());
    TO newVal = dynamic_cast<TO>(old);
    KU_ASSERT(newVal != nullptr);
    return newVal;
#else
    return reinterpret_cast<TO>(old);
#endif
}

template<typename TO, typename FROM>
TO ku_dynamic_cast(FROM& old) {
#if defined(LBUG_RUNTIME_CHECKS) || !defined(NDEBUG)
    static_assert(std::is_reference<TO>());
    try {
        TO newVal = dynamic_cast<TO>(old);
        return newVal;
    } catch (std::bad_cast& e) {
        KU_ASSERT(false);
    }
#else
    return reinterpret_cast<TO>(old);
#endif
}

} // namespace common
} // namespace lbug
