/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include <cassert>
#include <algorithm>
#include "thrift_export.h"

#include "transport/TBufferTransports.h"

using std::string;

namespace lbug_apache {
namespace thrift {
namespace transport {




void TMemoryBuffer::computeRead(uint32_t len, uint8_t** out_start, uint32_t* out_give) {
  // Correct rBound_ so we can use the fast path in the future.
  rBound_ = wBase_;

  // Decide how much to give.
  uint32_t give = (std::min)(len, available_read());

  *out_start = rBase_;
  *out_give = give;

  // Preincrement rBase_ so the caller doesn't have to.
  rBase_ += give;
}

uint32_t TMemoryBuffer::readSlow(uint8_t* buf, uint32_t len) {
  uint8_t* start;
  uint32_t give;
  computeRead(len, &start, &give);

  // Copy into the provided buffer.
  memcpy(buf, start, give);

  return give;
}

uint32_t TMemoryBuffer::readAppendToString(std::string& str, uint32_t len) {
  // Don't get some stupid assertion failure.
  if (buffer_ == nullptr) {
    return 0;
  }

  uint8_t* start;
  uint32_t give;
  computeRead(len, &start, &give);

  // Append to the provided string.
  str.append((char*)start, give);

  return give;
}

void TMemoryBuffer::ensureCanWrite(uint32_t len) {
  // Check available space
  uint32_t avail = available_write();
  if (len <= avail) {
    return;
  }

  if (!owner_) {
    throw TTransportException("Insufficient space in external MemoryBuffer");
  }

  // Grow the buffer as necessary.
  uint64_t new_size = bufferSize_;
  while (len > avail) {
    new_size = new_size > 0 ? new_size * 2 : 1;
    if (new_size > maxBufferSize_) {
      throw TTransportException(TTransportException::BAD_ARGS,
                                "Internal buffer size overflow");
    }
    avail = available_write() + (static_cast<uint32_t>(new_size) - bufferSize_);
  }

  // Allocate into a new pointer so we don't bork ours if it fails.
  auto* new_buffer = static_cast<uint8_t*>(std::realloc(buffer_, new_size));
  if (new_buffer == nullptr) {
    throw std::bad_alloc();
  }

  rBase_ = new_buffer + (rBase_ - buffer_);
  rBound_ = new_buffer + (rBound_ - buffer_);
  wBase_ = new_buffer + (wBase_ - buffer_);
  wBound_ = new_buffer + new_size;
  buffer_ = new_buffer;
  bufferSize_ = static_cast<uint32_t>(new_size);
}

void TMemoryBuffer::writeSlow(const uint8_t* buf, uint32_t len) {
  ensureCanWrite(len);

  // Copy into the buffer and increment wBase_.
  memcpy(wBase_, buf, len);
  wBase_ += len;
}

void TMemoryBuffer::wroteBytes(uint32_t len) {
  uint32_t avail = available_write();
  if (len > avail) {
    throw TTransportException("Client wrote more bytes than size of buffer.");
  }
  wBase_ += len;
}

const uint8_t* TMemoryBuffer::borrowSlow(uint8_t* buf, uint32_t* len) {
  (void)buf;
  rBound_ = wBase_;
  if (available_read() >= *len) {
    *len = available_read();
    return rBase_;
  }
  return nullptr;
}
}
}
} // lbug_apache::thrift::transport
