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

#include <mutex>
#include <shared_mutex>
#include <utility>

#if ANTLR4CPP_USING_ABSEIL
#include "absl/base/call_once.h"
#include "absl/base/thread_annotations.h"
#include "absl/synchronization/mutex.h"
#define ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS ABSL_NO_THREAD_SAFETY_ANALYSIS
#else
#define ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS
#endif

// By default ANTLRv4 uses synchronization primitives provided by the C++ standard library. In most
// deployments this is fine, however in some using custom synchronization primitives may be
// preferred. This header allows that by optionally supporting some alternative implementations and
// allowing for more easier patching of other alternatives.

namespace antlr4::internal {

  // Must be compatible with C++ standard library Mutex requirement.
  class ANTLR4CPP_PUBLIC Mutex final {
  public:
    Mutex() = default;

    // No copying or moving, we are as strict as possible to support other implementations.
    Mutex(const Mutex&) = delete;
    Mutex(Mutex&&) = delete;

    // No copying or moving, we are as strict as possible to support other implementations.
    Mutex& operator=(const Mutex&) = delete;
    Mutex& operator=(Mutex&&) = delete;

    void lock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    bool try_lock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    void unlock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

  private:
#if ANTLR4CPP_USING_ABSEIL
    absl::Mutex _impl;
#else
    std::mutex _impl;
#endif
  };

  template <typename Mutex>
  using UniqueLock = std::unique_lock<Mutex>;

  // Must be compatible with C++ standard library SharedMutex requirement.
  class ANTLR4CPP_PUBLIC SharedMutex final {
  public:
    SharedMutex() = default;

    // No copying or moving, we are as strict as possible to support other implementations.
    SharedMutex(const SharedMutex&) = delete;
    SharedMutex(SharedMutex&&) = delete;

    // No copying or moving, we are as strict as possible to support other implementations.
    SharedMutex& operator=(const SharedMutex&) = delete;
    SharedMutex& operator=(SharedMutex&&) = delete;

    void lock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    bool try_lock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    void unlock() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    void lock_shared() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    bool try_lock_shared() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

    void unlock_shared() ANTLR4CPP_NO_THREAD_SAFTEY_ANALYSIS;

  private:
#if ANTLR4CPP_USING_ABSEIL
    absl::Mutex _impl;
#else
    std::shared_mutex _impl;
#endif
  };

  template <typename Mutex>
  using SharedLock = std::shared_lock<Mutex>;

  class OnceFlag;

  template <typename Callable, typename... Args>
  void call_once(OnceFlag &onceFlag, Callable &&callable, Args&&... args);

  // Must be compatible with std::once_flag.
  class ANTLR4CPP_PUBLIC OnceFlag final {
  public:
    constexpr OnceFlag() = default;

    // No copying or moving, we are as strict as possible to support other implementations.
    OnceFlag(const OnceFlag&) = delete;
    OnceFlag(OnceFlag&&) = delete;

    // No copying or moving, we are as strict as possible to support other implementations.
    OnceFlag& operator=(const OnceFlag&) = delete;
    OnceFlag& operator=(OnceFlag&&) = delete;

  private:
    template <typename Callable, typename... Args>
    friend void call_once(OnceFlag &onceFlag, Callable &&callable, Args&&... args);

#if ANTLR4CPP_USING_ABSEIL
    absl::once_flag _impl;
#else
    std::once_flag _impl;
#endif
  };

  template <typename Callable, typename... Args>
  void call_once(OnceFlag &onceFlag, Callable &&callable, Args&&... args) {
#if ANTLR4CPP_USING_ABSEIL
    absl::call_once(onceFlag._impl, std::forward<Callable>(callable), std::forward<Args>(args)...);
#else
    std::call_once(onceFlag._impl, std::forward<Callable>(callable), std::forward<Args>(args)...);
#endif
  }

}  // namespace antlr4::internal
