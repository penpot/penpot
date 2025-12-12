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

#include <cstddef>
#include <cstdint>
#include <limits>

#include "antlr4-common.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC PredictionContextMergeCacheOptions final {
  public:
    PredictionContextMergeCacheOptions() = default;

    size_t getMaxSize() const { return _maxSize; }

    bool hasMaxSize() const { return getMaxSize() != std::numeric_limits<size_t>::max(); }

    PredictionContextMergeCacheOptions& setMaxSize(size_t maxSize) {
      _maxSize = maxSize;
      return *this;
    }

    size_t getClearEveryN() const {
      return _clearEveryN;
    }

    bool hasClearEveryN() const { return getClearEveryN() != 0; }

    PredictionContextMergeCacheOptions& setClearEveryN(uint64_t clearEveryN) {
      _clearEveryN = clearEveryN;
      return *this;
    }

    PredictionContextMergeCacheOptions& neverClear() {
      return setClearEveryN(0);
    }

  private:
    size_t _maxSize = std::numeric_limits<size_t>::max();
    uint64_t _clearEveryN = 1;
  };

}  // namespace atn
}  // namespace antlr4
