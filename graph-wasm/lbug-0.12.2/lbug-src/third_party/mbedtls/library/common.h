/**
 * \file common.h
 *
 * \brief Utility macros for internal use in the library
 */
/*
 *  Copyright The Mbed TLS Contributors
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

#ifndef MBEDTLS_LIBRARY_COMMON_H
#define MBEDTLS_LIBRARY_COMMON_H

#include <stdint.h>

#include "mbedtls/build_info.h"

/** Helper to define a function as static except when building invasive tests.
 *
 * If a function is only used inside its own source file and should be
 * declared `static` to allow the compiler to optimize for code size,
 * but that function has unit tests, define it with
 * ```
 * MBEDTLS_STATIC_TESTABLE int mbedtls_foo(...) { ... }
 * ```
 * and declare it in a header in the `library/` directory with
 * ```
 * #if defined(MBEDTLS_TEST_HOOKS)
 * int mbedtls_foo(...);
 * #endif
 * ```
 */
#if defined(MBEDTLS_TEST_HOOKS)
#define MBEDTLS_STATIC_TESTABLE
#else
#define MBEDTLS_STATIC_TESTABLE static
#endif

#if defined(MBEDTLS_TEST_HOOKS)
extern void (*mbedtls_test_hook_test_fail)(const char* test, int line, const char* file);
#define MBEDTLS_TEST_HOOK_TEST_ASSERT(TEST)                                                        \
    do {                                                                                           \
        if ((!(TEST)) && ((*mbedtls_test_hook_test_fail) != NULL)) {                               \
            (*mbedtls_test_hook_test_fail)(#TEST, __LINE__, __FILE__);                             \
        }                                                                                          \
    } while (0)
#else
#define MBEDTLS_TEST_HOOK_TEST_ASSERT(TEST)
#endif /* defined(MBEDTLS_TEST_HOOKS) */

/** Allow library to access its structs' private members.
 *
 * Although structs defined in header files are publicly available,
 * their members are private and should not be accessed by the user.
 */
#define MBEDTLS_ALLOW_PRIVATE_ACCESS

/** Byte Reading Macros
 *
 * Given a multi-byte integer \p x, MBEDTLS_BYTE_n retrieves the n-th
 * byte from x, where byte 0 is the least significant byte.
 */
#define MBEDTLS_BYTE_0(x) ((uint8_t)((x) & 0xff))
#define MBEDTLS_BYTE_1(x) ((uint8_t)(((x) >> 8) & 0xff))
#define MBEDTLS_BYTE_2(x) ((uint8_t)(((x) >> 16) & 0xff))
#define MBEDTLS_BYTE_3(x) ((uint8_t)(((x) >> 24) & 0xff))
#define MBEDTLS_BYTE_4(x) ((uint8_t)(((x) >> 32) & 0xff))
#define MBEDTLS_BYTE_5(x) ((uint8_t)(((x) >> 40) & 0xff))
#define MBEDTLS_BYTE_6(x) ((uint8_t)(((x) >> 48) & 0xff))
#define MBEDTLS_BYTE_7(x) ((uint8_t)(((x) >> 56) & 0xff))

/**
 * Get the unsigned 32 bits integer corresponding to four bytes in
 * big-endian order (MSB first).
 *
 * \param   data    Base address of the memory to get the four bytes from.
 * \param   offset  Offset from \p data of the first and most significant
 *                  byte of the four bytes to build the 32 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT32_BE
#define MBEDTLS_GET_UINT32_BE(data, offset)                                                        \
    (((uint32_t)(data)[(offset)] << 24) | ((uint32_t)(data)[(offset) + 1] << 16) |                 \
        ((uint32_t)(data)[(offset) + 2] << 8) | ((uint32_t)(data)[(offset) + 3]))
#endif

/**
 * Put in memory a 32 bits unsigned integer in big-endian order.
 *
 * \param   n       32 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 32
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the most significant
 *                  byte of the 32 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT32_BE
#define MBEDTLS_PUT_UINT32_BE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_3(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_2(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 3] = MBEDTLS_BYTE_0(n);                                                  \
    }
#endif

/**
 * Get the unsigned 32 bits integer corresponding to four bytes in
 * little-endian order (LSB first).
 *
 * \param   data    Base address of the memory to get the four bytes from.
 * \param   offset  Offset from \p data of the first and least significant
 *                  byte of the four bytes to build the 32 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT32_LE
#define MBEDTLS_GET_UINT32_LE(data, offset)                                                        \
    (((uint32_t)(data)[(offset)]) | ((uint32_t)(data)[(offset) + 1] << 8) |                        \
        ((uint32_t)(data)[(offset) + 2] << 16) | ((uint32_t)(data)[(offset) + 3] << 24))
#endif

/**
 * Put in memory a 32 bits unsigned integer in little-endian order.
 *
 * \param   n       32 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 32
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the least significant
 *                  byte of the 32 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT32_LE
#define MBEDTLS_PUT_UINT32_LE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_0(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_2(n);                                                  \
        (data)[(offset) + 3] = MBEDTLS_BYTE_3(n);                                                  \
    }
#endif

/**
 * Get the unsigned 16 bits integer corresponding to two bytes in
 * little-endian order (LSB first).
 *
 * \param   data    Base address of the memory to get the two bytes from.
 * \param   offset  Offset from \p data of the first and least significant
 *                  byte of the two bytes to build the 16 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT16_LE
#define MBEDTLS_GET_UINT16_LE(data, offset)                                                        \
    (((uint16_t)(data)[(offset)]) | ((uint16_t)(data)[(offset) + 1] << 8))
#endif

/**
 * Put in memory a 16 bits unsigned integer in little-endian order.
 *
 * \param   n       16 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 16
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the least significant
 *                  byte of the 16 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT16_LE
#define MBEDTLS_PUT_UINT16_LE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_0(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_1(n);                                                  \
    }
#endif

/**
 * Get the unsigned 16 bits integer corresponding to two bytes in
 * big-endian order (MSB first).
 *
 * \param   data    Base address of the memory to get the two bytes from.
 * \param   offset  Offset from \p data of the first and most significant
 *                  byte of the two bytes to build the 16 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT16_BE
#define MBEDTLS_GET_UINT16_BE(data, offset)                                                        \
    (((uint16_t)(data)[(offset)] << 8) | ((uint16_t)(data)[(offset) + 1]))
#endif

/**
 * Put in memory a 16 bits unsigned integer in big-endian order.
 *
 * \param   n       16 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 16
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the most significant
 *                  byte of the 16 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT16_BE
#define MBEDTLS_PUT_UINT16_BE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_1(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_0(n);                                                  \
    }
#endif

/**
 * Get the unsigned 24 bits integer corresponding to three bytes in
 * big-endian order (MSB first).
 *
 * \param   data    Base address of the memory to get the three bytes from.
 * \param   offset  Offset from \p data of the first and most significant
 *                  byte of the three bytes to build the 24 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT24_BE
#define MBEDTLS_GET_UINT24_BE(data, offset)                                                        \
    (((uint32_t)(data)[(offset)] << 16) | ((uint32_t)(data)[(offset) + 1] << 8) |                  \
        ((uint32_t)(data)[(offset) + 2]))
#endif

/**
 * Put in memory a 24 bits unsigned integer in big-endian order.
 *
 * \param   n       24 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 24
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the most significant
 *                  byte of the 24 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT24_BE
#define MBEDTLS_PUT_UINT24_BE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_2(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_0(n);                                                  \
    }
#endif

/**
 * Get the unsigned 24 bits integer corresponding to three bytes in
 * little-endian order (LSB first).
 *
 * \param   data    Base address of the memory to get the three bytes from.
 * \param   offset  Offset from \p data of the first and least significant
 *                  byte of the three bytes to build the 24 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT24_LE
#define MBEDTLS_GET_UINT24_LE(data, offset)                                                        \
    (((uint32_t)(data)[(offset)]) | ((uint32_t)(data)[(offset) + 1] << 8) |                        \
        ((uint32_t)(data)[(offset) + 2] << 16))
#endif

/**
 * Put in memory a 24 bits unsigned integer in little-endian order.
 *
 * \param   n       24 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 24
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the least significant
 *                  byte of the 24 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT24_LE
#define MBEDTLS_PUT_UINT24_LE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_0(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_2(n);                                                  \
    }
#endif

/**
 * Get the unsigned 64 bits integer corresponding to eight bytes in
 * big-endian order (MSB first).
 *
 * \param   data    Base address of the memory to get the eight bytes from.
 * \param   offset  Offset from \p data of the first and most significant
 *                  byte of the eight bytes to build the 64 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT64_BE
#define MBEDTLS_GET_UINT64_BE(data, offset)                                                        \
    (((uint64_t)(data)[(offset)] << 56) | ((uint64_t)(data)[(offset) + 1] << 48) |                 \
        ((uint64_t)(data)[(offset) + 2] << 40) | ((uint64_t)(data)[(offset) + 3] << 32) |          \
        ((uint64_t)(data)[(offset) + 4] << 24) | ((uint64_t)(data)[(offset) + 5] << 16) |          \
        ((uint64_t)(data)[(offset) + 6] << 8) | ((uint64_t)(data)[(offset) + 7]))
#endif

/**
 * Put in memory a 64 bits unsigned integer in big-endian order.
 *
 * \param   n       64 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 64
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the most significant
 *                  byte of the 64 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT64_BE
#define MBEDTLS_PUT_UINT64_BE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_7(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_6(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_5(n);                                                  \
        (data)[(offset) + 3] = MBEDTLS_BYTE_4(n);                                                  \
        (data)[(offset) + 4] = MBEDTLS_BYTE_3(n);                                                  \
        (data)[(offset) + 5] = MBEDTLS_BYTE_2(n);                                                  \
        (data)[(offset) + 6] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 7] = MBEDTLS_BYTE_0(n);                                                  \
    }
#endif

/**
 * Get the unsigned 64 bits integer corresponding to eight bytes in
 * little-endian order (LSB first).
 *
 * \param   data    Base address of the memory to get the eight bytes from.
 * \param   offset  Offset from \p data of the first and least significant
 *                  byte of the eight bytes to build the 64 bits unsigned
 *                  integer from.
 */
#ifndef MBEDTLS_GET_UINT64_LE
#define MBEDTLS_GET_UINT64_LE(data, offset)                                                        \
    (((uint64_t)(data)[(offset) + 7] << 56) | ((uint64_t)(data)[(offset) + 6] << 48) |             \
        ((uint64_t)(data)[(offset) + 5] << 40) | ((uint64_t)(data)[(offset) + 4] << 32) |          \
        ((uint64_t)(data)[(offset) + 3] << 24) | ((uint64_t)(data)[(offset) + 2] << 16) |          \
        ((uint64_t)(data)[(offset) + 1] << 8) | ((uint64_t)(data)[(offset)]))
#endif

/**
 * Put in memory a 64 bits unsigned integer in little-endian order.
 *
 * \param   n       64 bits unsigned integer to put in memory.
 * \param   data    Base address of the memory where to put the 64
 *                  bits unsigned integer in.
 * \param   offset  Offset from \p data where to put the least significant
 *                  byte of the 64 bits unsigned integer \p n.
 */
#ifndef MBEDTLS_PUT_UINT64_LE
#define MBEDTLS_PUT_UINT64_LE(n, data, offset)                                                     \
    {                                                                                              \
        (data)[(offset)] = MBEDTLS_BYTE_0(n);                                                      \
        (data)[(offset) + 1] = MBEDTLS_BYTE_1(n);                                                  \
        (data)[(offset) + 2] = MBEDTLS_BYTE_2(n);                                                  \
        (data)[(offset) + 3] = MBEDTLS_BYTE_3(n);                                                  \
        (data)[(offset) + 4] = MBEDTLS_BYTE_4(n);                                                  \
        (data)[(offset) + 5] = MBEDTLS_BYTE_5(n);                                                  \
        (data)[(offset) + 6] = MBEDTLS_BYTE_6(n);                                                  \
        (data)[(offset) + 7] = MBEDTLS_BYTE_7(n);                                                  \
    }
#endif

/* Fix MSVC C99 compatible issue
 *      MSVC support __func__ from visual studio 2015( 1900 )
 *      Use MSVC predefine macro to avoid name check fail.
 */
#if (defined(_MSC_VER) && (_MSC_VER <= 1900))
#define /*no-check-names*/ __func__ __FUNCTION__
#endif

#endif /* MBEDTLS_LIBRARY_COMMON_H */
