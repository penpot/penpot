// Copyright 2012-2022 The ANTLR Project
//
// Redistribution and use in source and binary forms, with or without modification, are permitted
// provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this list of conditions
//    and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of
//    conditions and the following disclaimer in the documentation and/or other materials provided
//    with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its contributors may be used to
//    endorse or promote products derived from this software without specific prior written
//    permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
// IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
// FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
// WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

#pragma once

#include "antlr4-common.h"

#if ANTLR4CPP_USING_ABSEIL
#include "absl/container/flat_hash_set.h"
#else
#include <unordered_set>
#endif

// By default ANTLRv4 uses containers provided by the C++ standard library. In most deployments this
// is fine, however in some using custom containers may be preferred. This header allows that by
// optionally supporting some alternative implementations and allowing for more easier patching of
// other alternatives.

namespace antlr4 {

#if ANTLR4CPP_USING_ABSEIL
  template <typename Key,
            typename Hash = typename absl::flat_hash_set<Key>::hasher,
            typename Equal = typename absl::flat_hash_set<Key>::key_equal,
            typename Allocator = typename absl::flat_hash_set<Key>::allocator_type>
  using FlatHashSet = absl::flat_hash_set<Key, Hash, Equal, Allocator>;
#else
  template <typename Key,
            typename Hash = std::hash<Key>,
            typename Equal = std::equal_to<Key>,
            typename Allocator = std::allocator<Key>>
  using FlatHashSet = std::unordered_set<Key, Hash, Equal, Allocator>;
#endif

} // namespace antlr4
