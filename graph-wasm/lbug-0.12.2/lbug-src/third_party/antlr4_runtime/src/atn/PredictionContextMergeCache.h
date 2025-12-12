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

#include <utility>

#include "atn/PredictionContext.h"
#include "atn/PredictionContextMergeCacheOptions.h"
#include "FlatHashMap.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC PredictionContextMergeCache final {
  public:
    PredictionContextMergeCache()
        : PredictionContextMergeCache(PredictionContextMergeCacheOptions()) {}

    explicit PredictionContextMergeCache(const PredictionContextMergeCacheOptions &options);

    PredictionContextMergeCache(const PredictionContextMergeCache&) = delete;
    PredictionContextMergeCache(PredictionContextMergeCache&&) = delete;

    PredictionContextMergeCache& operator=(const PredictionContextMergeCache&) = delete;
    PredictionContextMergeCache& operator=(PredictionContextMergeCache&&) = delete;

    Ref<const PredictionContext> put(const Ref<const PredictionContext> &key1,
                                     const Ref<const PredictionContext> &key2,
                                     Ref<const PredictionContext> value);

    Ref<const PredictionContext> get(const Ref<const PredictionContext> &key1,
                                     const Ref<const PredictionContext> &key2) const;

    const PredictionContextMergeCacheOptions& getOptions() const { return _options; }

    void clear();

  private:
    using PredictionContextPair = std::pair<const PredictionContext*, const PredictionContext*>;

    struct ANTLR4CPP_PUBLIC PredictionContextHasher final {
      size_t operator()(const PredictionContextPair &value) const;
    };

    struct ANTLR4CPP_PUBLIC PredictionContextComparer final {
      bool operator()(const PredictionContextPair &lhs, const PredictionContextPair &rhs) const;
    };

    struct ANTLR4CPP_PUBLIC Entry final {
      std::pair<Ref<const PredictionContext>, Ref<const PredictionContext>> key;
      Ref<const PredictionContext> value;
      Entry *prev = nullptr;
      Entry *next = nullptr;
    };

    void moveToFront(Entry *entry) const;

    void pushToFront(Entry *entry);

    void remove(Entry *entry);

    void compact(const Entry *preserve);

    using Container = FlatHashMap<PredictionContextPair, std::unique_ptr<Entry>,
                                  PredictionContextHasher, PredictionContextComparer>;

    const PredictionContextMergeCacheOptions _options;

    Container _entries;

    mutable Entry *_head = nullptr;
    mutable Entry *_tail = nullptr;

    size_t _size = 0;
  };

}  // namespace atn
}  // namespace antlr4
