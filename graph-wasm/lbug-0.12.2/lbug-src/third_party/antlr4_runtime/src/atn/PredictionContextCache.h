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

#include "atn/PredictionContext.h"
#include "FlatHashSet.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC PredictionContextCache final {
  public:
    PredictionContextCache() = default;

    PredictionContextCache(const PredictionContextCache&) = delete;
    PredictionContextCache(PredictionContextCache&&) = delete;

    PredictionContextCache& operator=(const PredictionContextCache&) = delete;
    PredictionContextCache& operator=(PredictionContextCache&&) = delete;

    void put(const Ref<const PredictionContext> &value);

    Ref<const PredictionContext> get(const Ref<const PredictionContext> &value) const;

  private:
    struct ANTLR4CPP_PUBLIC PredictionContextHasher final {
      size_t operator()(const Ref<const PredictionContext> &predictionContext) const;
    };

    struct ANTLR4CPP_PUBLIC PredictionContextComparer final {
      bool operator()(const Ref<const PredictionContext> &lhs,
                      const Ref<const PredictionContext> &rhs) const;
    };

    FlatHashSet<Ref<const PredictionContext>,
                PredictionContextHasher, PredictionContextComparer> _data;
  };

}  // namespace atn
}  // namespace antlr4
