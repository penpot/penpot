/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include <cstddef>
#include <cstdint>
#include <cstring>

#include "misc/MurmurHash.h"

using namespace antlr4::misc;

// A variation of the MurmurHash3 implementation (https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp)
// Here we unrolled the loop used there into individual calls to update(), as we usually hash object fields
// instead of entire buffers.

// Platform-specific functions and macros

// Microsoft Visual Studio

#if defined(_MSC_VER)

#include <stdlib.h>

#define ROTL32(x,y)	_rotl(x,y)
#define ROTL64(x,y)	_rotl64(x,y)

#elif ANTLR4CPP_HAVE_BUILTIN(__builtin_rotateleft32) && ANTLR4CPP_HAVE_BUILTIN(__builtin_rotateleft64)

#define ROTL32(x, y) __builtin_rotateleft32(x, y)
#define ROTL64(x, y) __builtin_rotateleft64(x, y)

#else	// defined(_MSC_VER)

// Other compilers

namespace {

constexpr uint32_t ROTL32(uint32_t x, int r) {
  return (x << r) | (x >> (32 - r));
}
constexpr uint64_t ROTL64(uint64_t x, int r) {
  return (x << r) | (x >> (64 - r));
}

}

#endif // !defined(_MSC_VER)

#if SIZE_MAX == UINT64_MAX

size_t MurmurHash::update(size_t hash, size_t value) {
  size_t k1 = value;
  k1 *= UINT64_C(0x87c37b91114253d5);
  k1 = ROTL64(k1, 31);
  k1 *= UINT64_C(0x4cf5ad432745937f);

  hash ^= k1;
  hash = ROTL64(hash, 27);
  hash = hash * 5 + UINT64_C(0x52dce729);

  return hash;
}

size_t MurmurHash::finish(size_t hash, size_t entryCount) {
  hash ^= entryCount * 8;
  hash ^= hash >> 33;
  hash *= UINT64_C(0xff51afd7ed558ccd);
  hash ^= hash >> 33;
  hash *= UINT64_C(0xc4ceb9fe1a85ec53);
  hash ^= hash >> 33;
  return hash;
}

#elif SIZE_MAX == UINT32_MAX

size_t MurmurHash::update(size_t hash, size_t value) {
  size_t k1 = value;
  k1 *= UINT32_C(0xCC9E2D51);
  k1 = ROTL32(k1, 15);
  k1 *= UINT32_C(0x1B873593);

  hash ^= k1;
  hash = ROTL32(hash, 13);
  hash = hash * 5 + UINT32_C(0xE6546B64);

  return hash;
}

size_t MurmurHash::finish(size_t hash, size_t entryCount) {
  hash ^= entryCount * 4;
  hash ^= hash >> 16;
  hash *= UINT32_C(0x85EBCA6B);
  hash ^= hash >> 13;
  hash *= UINT32_C(0xC2B2AE35);
  hash ^= hash >> 16;
  return hash;
}

#else
#error "Expected sizeof(size_t) to be 4 or 8."
#endif

size_t MurmurHash::update(size_t hash, const void *data, size_t size) {
  size_t value;
  const uint8_t *bytes = static_cast<const uint8_t*>(data);
  while (size >= sizeof(size_t)) {
    std::memcpy(&value, bytes, sizeof(size_t));
    hash = update(hash, value);
    bytes += sizeof(size_t);
    size -= sizeof(size_t);
  }
  if (size != 0) {
    value = 0;
    std::memcpy(&value, bytes, size);
    hash = update(hash, value);
  }
  return hash;
}
