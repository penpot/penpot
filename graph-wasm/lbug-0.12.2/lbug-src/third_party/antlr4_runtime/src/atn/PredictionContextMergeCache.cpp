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

#include "atn/PredictionContextMergeCache.h"

#include "misc/MurmurHash.h"

using namespace antlr4::atn;
using namespace antlr4::misc;

PredictionContextMergeCache::PredictionContextMergeCache(
    const PredictionContextMergeCacheOptions &options) : _options(options) {}

Ref<const PredictionContext> PredictionContextMergeCache::put(
    const Ref<const PredictionContext> &key1,
    const Ref<const PredictionContext> &key2,
    Ref<const PredictionContext> value) {
  assert(key1);
  assert(key2);

  if (getOptions().getMaxSize() == 0) {
    // Cache is effectively disabled.
    return value;
  }

  auto [existing, inserted] = _entries.try_emplace(std::make_pair(key1.get(), key2.get()));
  if (inserted) {
    try {
      existing->second.reset(new Entry());
    } catch (...) {
      _entries.erase(existing);
      throw;
    }
    existing->second->key = std::make_pair(key1, key2);
    existing->second->value = std::move(value);
    pushToFront(existing->second.get());
  } else {
    if (existing->second->value != value) {
      existing->second->value = std::move(value);
    }
    moveToFront(existing->second.get());
  }
  compact(existing->second.get());
  return existing->second->value;
}

Ref<const PredictionContext> PredictionContextMergeCache::get(
    const Ref<const PredictionContext> &key1,
    const Ref<const PredictionContext> &key2) const {
  assert(key1);
  assert(key2);

  if (getOptions().getMaxSize() == 0) {
    // Cache is effectively disabled.
    return nullptr;
  }

  auto iterator = _entries.find(std::make_pair(key1.get(), key2.get()));
  if (iterator == _entries.end()) {
    return nullptr;
  }
  moveToFront(iterator->second.get());
  return iterator->second->value;
}

void PredictionContextMergeCache::clear() {
  Container().swap(_entries);
  _head = _tail = nullptr;
  _size = 0;
}

void PredictionContextMergeCache::moveToFront(Entry *entry) const {
  if (entry->prev == nullptr) {
    assert(entry == _head);
    return;
  }
  entry->prev->next = entry->next;
  if (entry->next != nullptr) {
    entry->next->prev = entry->prev;
  } else {
    assert(entry == _tail);
    _tail = entry->prev;
  }
  entry->prev = nullptr;
  entry->next = _head;
  _head->prev = entry;
  _head = entry;
  assert(entry->prev == nullptr);
}

void PredictionContextMergeCache::pushToFront(Entry *entry) {
  ++_size;
  entry->prev = nullptr;
  entry->next = _head;
  if (_head != nullptr) {
    _head->prev = entry;
    _head = entry;
  } else {
    assert(entry->next == nullptr);
    _head = entry;
    _tail = entry;
  }
  assert(entry->prev == nullptr);
}

void PredictionContextMergeCache::remove(Entry *entry) {
  if (entry->prev != nullptr) {
    entry->prev->next = entry->next;
  } else {
    assert(entry == _head);
    _head = entry->next;
  }
  if (entry->next != nullptr) {
    entry->next->prev = entry->prev;
  } else {
    assert(entry == _tail);
    _tail = entry->prev;
  }
  --_size;
  _entries.erase(std::make_pair(entry->key.first.get(), entry->key.second.get()));
}

void PredictionContextMergeCache::compact(const Entry *preserve) {
  Entry *entry = _tail;
  while (entry != nullptr && _size > getOptions().getMaxSize()) {
    Entry *next = entry->prev;
    if (entry != preserve) {
      remove(entry);
    }
    entry = next;
  }
}

size_t PredictionContextMergeCache::PredictionContextHasher::operator()(
    const PredictionContextPair &value) const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, value.first->hashCode());
  hash = MurmurHash::update(hash, value.second->hashCode());
  return MurmurHash::finish(hash, 2);
}

bool PredictionContextMergeCache::PredictionContextComparer::operator()(
    const PredictionContextPair &lhs, const PredictionContextPair &rhs) const {
  return *lhs.first == *rhs.first && *lhs.second == *rhs.second;
}
