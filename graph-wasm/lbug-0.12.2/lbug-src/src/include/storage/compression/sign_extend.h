#pragma once

/* Adapted from
 * https://github.com/duckdb/duckdb/blob/312b9954507386305544a42c4f43c2bd410a64cb/src/include/duckdb/common/bitpacking.hpp#L190-L199
 *
 * Copyright 2018-2023 Stichting DuckDB Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 */

#include <string.h>

#include <cstdint>

#include "common/assert.h"
#include "common/numeric_utils.h"
#include "common/utils.h"

namespace lbug {
namespace storage {

template<typename T>
void Store(const T& val, uint8_t* ptr) {
    memcpy(ptr, (void*)&val, sizeof(val));
}

template<typename T>
T Load(const uint8_t* ptr) {
    T ret{};
    memcpy(&ret, ptr, sizeof(ret));
    return ret;
}

// Sign bit extension
template<class T, class T_U = typename common::numeric_utils::MakeUnSignedT<T>, uint64_t CHUNK_SIZE>
static void SignExtend(uint8_t* dst, uint8_t width) {
    KU_ASSERT(width < sizeof(T) * 8);
    T const mask = T_U(1) << (width - 1);
    for (uint64_t i = 0; i < CHUNK_SIZE; ++i) {
        T value = Load<T>(dst + i * sizeof(T));
        const T_U andMask = common::BitmaskUtils::all1sMaskForLeastSignificantBits<T_U>(width);
        value = value & andMask;
        T result = (value ^ mask) - mask;
        Store(result, dst + i * sizeof(T));
    }
}

} // namespace storage
} // namespace lbug
