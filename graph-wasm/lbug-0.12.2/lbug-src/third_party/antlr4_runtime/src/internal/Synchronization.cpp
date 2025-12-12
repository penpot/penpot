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

#include "internal/Synchronization.h"

using namespace antlr4::internal;

void Mutex::lock() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.Lock();
#else
  _impl.lock();
#endif
}

bool Mutex::try_lock() {
#if ANTLR4CPP_USING_ABSEIL
  return _impl.TryLock();
#else
  return _impl.try_lock();
#endif
}

void Mutex::unlock() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.Unlock();
#else
  _impl.unlock();
#endif
}

void SharedMutex::lock() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.WriterLock();
#else
  _impl.lock();
#endif
}

bool SharedMutex::try_lock() {
#if ANTLR4CPP_USING_ABSEIL
  return _impl.WriterTryLock();
#else
  return _impl.try_lock();
#endif
}

void SharedMutex::unlock() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.WriterUnlock();
#else
  _impl.unlock();
#endif
}

void SharedMutex::lock_shared() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.ReaderLock();
#else
  _impl.lock_shared();
#endif
}

bool SharedMutex::try_lock_shared() {
#if ANTLR4CPP_USING_ABSEIL
  return _impl.ReaderTryLock();
#else
  return _impl.try_lock_shared();
#endif
}

void SharedMutex::unlock_shared() {
#if ANTLR4CPP_USING_ABSEIL
  _impl.ReaderUnlock();
#else
  _impl.unlock_shared();
#endif
}
