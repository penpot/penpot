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

#ifndef _LBUG_THRIFT_TRANSPORT_TBUFFERTRANSPORTS_H_
#define _LBUG_THRIFT_TRANSPORT_TBUFFERTRANSPORTS_H_ 1

#include <cstdlib>
#include <cstddef>
#include <cstring>
#include <limits>

#include "transport/TTransport.h"
#include "transport/TVirtualTransport.h"

#ifdef __GNUC__
#define TDB_LIKELY(val) (__builtin_expect((val), 1))
#define TDB_UNLIKELY(val) (__builtin_expect((val), 0))
#else
#define TDB_LIKELY(val) (val)
#define TDB_UNLIKELY(val) (val)
#endif

namespace lbug_apache {
namespace thrift {
namespace transport {

/**
 * Base class for all transports that use read/write buffers for performance.
 *
 * TBufferBase is designed to implement the fast-path "memcpy" style
 * operations that work in the common case.  It does so with small and
 * (eventually) nonvirtual, inlinable methods.  TBufferBase is an abstract
 * class.  Subclasses are expected to define the "slow path" operations
 * that have to be done when the buffers are full or empty.
 *
 */
class TBufferBase : public TVirtualTransport<TBufferBase> {

public:
  /**
   * Fast-path read.
   *
   * When we have enough data buffered to fulfill the read, we can satisfy it
   * with a single memcpy, then adjust our internal pointers.  If the buffer
   * is empty, we call out to our slow path, implemented by a subclass.
   * This method is meant to eventually be nonvirtual and inlinable.
   */
  uint32_t read(uint8_t* buf, uint32_t len) {
    uint8_t* new_rBase = rBase_ + len;
    if (TDB_LIKELY(new_rBase <= rBound_)) {
      std::memcpy(buf, rBase_, len);
      rBase_ = new_rBase;
      return len;
    }
    return readSlow(buf, len);
  }

  /**
   * Shortcutted version of readAll.
   */
  uint32_t readAll(uint8_t* buf, uint32_t len) {
    uint8_t* new_rBase = rBase_ + len;
    if (TDB_LIKELY(new_rBase <= rBound_)) {
      std::memcpy(buf, rBase_, len);
      rBase_ = new_rBase;
      return len;
    }
    return lbug_apache::thrift::transport::readAll(*this, buf, len);
  }

  /**
   * Fast-path write.
   *
   * When we have enough empty space in our buffer to accommodate the write, we
   * can satisfy it with a single memcpy, then adjust our internal pointers.
   * If the buffer is full, we call out to our slow path, implemented by a
   * subclass.  This method is meant to eventually be nonvirtual and
   * inlinable.
   */
  void write(const uint8_t* buf, uint32_t len) {
    uint8_t* new_wBase = wBase_ + len;
    if (TDB_LIKELY(new_wBase <= wBound_)) {
      std::memcpy(wBase_, buf, len);
      wBase_ = new_wBase;
      return;
    }
    writeSlow(buf, len);
  }

  /**
   * Fast-path borrow.  A lot like the fast-path read.
   */
  const uint8_t* borrow(uint8_t* buf, uint32_t* len) {
    if (TDB_LIKELY(static_cast<ptrdiff_t>(*len) <= rBound_ - rBase_)) {
      // With strict aliasing, writing to len shouldn't force us to
      // refetch rBase_ from memory.  TODO(dreiss): Verify this.
      *len = static_cast<uint32_t>(rBound_ - rBase_);
      return rBase_;
    }
    return borrowSlow(buf, len);
  }

  /**
   * Consume doesn't require a slow path.
   */
  void consume(uint32_t len) {
    if (TDB_LIKELY(static_cast<ptrdiff_t>(len) <= rBound_ - rBase_)) {
      rBase_ += len;
    } else {
      throw TTransportException(TTransportException::BAD_ARGS, "consume did not follow a borrow.");
    }
  }

protected:
  /// Slow path read.
  virtual uint32_t readSlow(uint8_t* buf, uint32_t len) = 0;

  /// Slow path write.
  virtual void writeSlow(const uint8_t* buf, uint32_t len) = 0;

  /**
   * Slow path borrow.
   *
   * POSTCONDITION: return == NULL || rBound_ - rBase_ >= *len
   */
  virtual const uint8_t* borrowSlow(uint8_t* buf, uint32_t* len) = 0;

  /**
   * Trivial constructor.
   *
   * Initialize pointers safely.  Constructing is not a very
   * performance-sensitive operation, so it is okay to just leave it to
   * the concrete class to set up pointers correctly.
   */
  TBufferBase() : rBase_(nullptr), rBound_(nullptr), wBase_(nullptr), wBound_(nullptr) {}

  /// Convenience mutator for setting the read buffer.
  void setReadBuffer(uint8_t* buf, uint32_t len) {
    rBase_ = buf;
    rBound_ = buf + len;
  }

  /// Convenience mutator for setting the write buffer.
  void setWriteBuffer(uint8_t* buf, uint32_t len) {
    wBase_ = buf;
    wBound_ = buf + len;
  }

  ~TBufferBase() override = default;

  /// Reads begin here.
  uint8_t* rBase_;
  /// Reads may extend to just before here.
  uint8_t* rBound_;

  /// Writes begin here.
  uint8_t* wBase_;
  /// Writes may extend to just before here.
  uint8_t* wBound_;
};


/**
 * A memory buffer is a transport that simply reads from and writes to an
 * in memory buffer. Anytime you call write on it, the data is simply placed
 * into a buffer, and anytime you call read, data is read from that buffer.
 *
 * The buffers are allocated using C constructs malloc,realloc, and the size
 * doubles as necessary.  We've considered using scoped
 *
 */
class TMemoryBuffer : public TVirtualTransport<TMemoryBuffer, TBufferBase> {
private:
  // Common initialization done by all constructors.
  void initCommon(uint8_t* buf, uint32_t size, bool owner, uint32_t wPos) {

    maxBufferSize_ = (std::numeric_limits<uint32_t>::max)();

    if (buf == nullptr && size != 0) {
      assert(owner);
      buf = (uint8_t*)std::malloc(size);
      if (buf == nullptr) {
	throw std::bad_alloc();
      }
    }

    buffer_ = buf;
    bufferSize_ = size;

    rBase_ = buffer_;
    rBound_ = buffer_ + wPos;
    // TODO(dreiss): Investigate NULL-ing this if !owner.
    wBase_ = buffer_ + wPos;
    wBound_ = buffer_ + bufferSize_;

    owner_ = owner;

    // rBound_ is really an artifact.  In principle, it should always be
    // equal to wBase_.  We update it in a few places (computeRead, etc.).
  }

public:
  static const uint32_t defaultSize = 1024;

  /**
   * This enum specifies how a TMemoryBuffer should treat
   * memory passed to it via constructors or resetBuffer.
   *
   * OBSERVE:
   *   TMemoryBuffer will simply store a pointer to the memory.
   *   It is the callers responsibility to ensure that the pointer
   *   remains valid for the lifetime of the TMemoryBuffer,
   *   and that it is properly cleaned up.
   *   Note that no data can be written to observed buffers.
   *
   * COPY:
   *   TMemoryBuffer will make an internal copy of the buffer.
   *   The caller has no responsibilities.
   *
   * TAKE_OWNERSHIP:
   *   TMemoryBuffer will become the "owner" of the buffer,
   *   and will be responsible for freeing it.
   *   The membory must have been allocated with malloc.
   */
  enum MemoryPolicy { OBSERVE = 1, COPY = 2, TAKE_OWNERSHIP = 3 };

  /**
   * Construct a TMemoryBuffer with a default-sized buffer,
   * owned by the TMemoryBuffer object.
   */
  TMemoryBuffer() { initCommon(nullptr, defaultSize, true, 0); }

  /**
   * Construct a TMemoryBuffer with a buffer of a specified size,
   * owned by the TMemoryBuffer object.
   *
   * @param sz  The initial size of the buffer.
   */
  TMemoryBuffer(uint32_t sz) { initCommon(nullptr, sz, true, 0); }

  /**
   * Construct a TMemoryBuffer with buf as its initial contents.
   *
   * @param buf    The initial contents of the buffer.
   *               Note that, while buf is a non-const pointer,
   *               TMemoryBuffer will not write to it if policy == OBSERVE,
   *               so it is safe to const_cast<uint8_t*>(whatever).
   * @param sz     The size of @c buf.
   * @param policy See @link MemoryPolicy @endlink .
   */
  TMemoryBuffer(uint8_t* buf, uint32_t sz, MemoryPolicy policy = OBSERVE) {
    if (buf == nullptr && sz != 0) {
      throw TTransportException(TTransportException::BAD_ARGS,
                                "TMemoryBuffer given null buffer with non-zero size.");
    }

    switch (policy) {
    case OBSERVE:
    case TAKE_OWNERSHIP:
      initCommon(buf, sz, policy == TAKE_OWNERSHIP, sz);
      break;
    case COPY:
      initCommon(nullptr, sz, true, 0);
      this->write(buf, sz);
      break;
    default:
      throw TTransportException(TTransportException::BAD_ARGS,
                                "Invalid MemoryPolicy for TMemoryBuffer");
    }
  }

  ~TMemoryBuffer() override {
    if (owner_) {
      std::free(buffer_);
    }
  }

  bool isOpen() const override { return true; }

  bool peek() override { return (rBase_ < wBase_); }

  void open() override {}

  void close() override {}

  // TODO(dreiss): Make bufPtr const.
  void getBuffer(uint8_t** bufPtr, uint32_t* sz) {
    *bufPtr = rBase_;
    *sz = static_cast<uint32_t>(wBase_ - rBase_);
  }

  std::string getBufferAsString() {
    if (buffer_ == nullptr) {
      return "";
    }
    uint8_t* buf;
    uint32_t sz;
    getBuffer(&buf, &sz);
    return std::string((char*)buf, (std::string::size_type)sz);
  }

  void appendBufferToString(std::string& str) {
    if (buffer_ == nullptr) {
      return;
    }
    uint8_t* buf;
    uint32_t sz;
    getBuffer(&buf, &sz);
    str.append((char*)buf, sz);
  }

  void resetBuffer() {
    rBase_ = buffer_;
    rBound_ = buffer_;
    wBase_ = buffer_;
    // It isn't safe to write into a buffer we don't own.
    if (!owner_) {
      wBound_ = wBase_;
      bufferSize_ = 0;
    }
  }

  /// See constructor documentation.
  void resetBuffer(uint8_t* buf, uint32_t sz, MemoryPolicy policy = OBSERVE) {
    // Use a variant of the copy-and-swap trick for assignment operators.
    // This is sub-optimal in terms of performance for two reasons:
    //   1/ The constructing and swapping of the (small) values
    //      in the temporary object takes some time, and is not necessary.
    //   2/ If policy == COPY, we allocate the new buffer before
    //      freeing the old one, precluding the possibility of
    //      reusing that memory.
    // I doubt that either of these problems could be optimized away,
    // but the second is probably no a common case, and the first is minor.
    // I don't expect resetBuffer to be a common operation, so I'm willing to
    // bite the performance bullet to make the method this simple.

    // Construct the new buffer.
    TMemoryBuffer new_buffer(buf, sz, policy);
    // Move it into ourself.
    this->swap(new_buffer);
    // Our old self gets destroyed.
  }

  /// See constructor documentation.
  void resetBuffer(uint32_t sz) {
    // Construct the new buffer.
    TMemoryBuffer new_buffer(sz);
    // Move it into ourself.
    this->swap(new_buffer);
    // Our old self gets destroyed.
  }

  std::string readAsString(uint32_t len) {
    std::string str;
    (void)readAppendToString(str, len);
    return str;
  }

  uint32_t readAppendToString(std::string& str, uint32_t len);

  // return number of bytes read
  uint32_t readEnd() override {
    // This cast should be safe, because buffer_'s size is a uint32_t
    auto bytes = static_cast<uint32_t>(rBase_ - buffer_);
    if (rBase_ == wBase_) {
      resetBuffer();
    }
    return bytes;
  }

  // Return number of bytes written
  uint32_t writeEnd() override {
    // This cast should be safe, because buffer_'s size is a uint32_t
    return static_cast<uint32_t>(wBase_ - buffer_);
  }

  uint32_t available_read() const {
    // Remember, wBase_ is the real rBound_.
    return static_cast<uint32_t>(wBase_ - rBase_);
  }

  uint32_t available_write() const { return static_cast<uint32_t>(wBound_ - wBase_); }

  // Returns a pointer to where the client can write data to append to
  // the TMemoryBuffer, and ensures the buffer is big enough to accommodate a
  // write of the provided length.  The returned pointer is very convenient for
  // passing to read(), recv(), or similar. You must call wroteBytes() as soon
  // as data is written or the buffer will not be aware that data has changed.
  uint8_t* getWritePtr(uint32_t len) {
    ensureCanWrite(len);
    return wBase_;
  }

  // Informs the buffer that the client has written 'len' bytes into storage
  // that had been provided by getWritePtr().
  void wroteBytes(uint32_t len);

  /*
   * TVirtualTransport provides a default implementation of readAll().
   * We want to use the TBufferBase version instead.
   */
  uint32_t readAll(uint8_t* buf, uint32_t len) { return TBufferBase::readAll(buf, len); }

  //! \brief Get the current buffer size
  //! \returns the current buffer size
  uint32_t getBufferSize() const {
    return bufferSize_;
  }

  //! \brief Get the current maximum buffer size
  //! \returns the current maximum buffer size
  uint32_t getMaxBufferSize() const {
    return maxBufferSize_;
  }

  //! \brief Change the maximum buffer size
  //! \param[in]  maxSize  the new maximum buffer size allowed to grow to
  //! \throws  TTransportException(BAD_ARGS) if maxSize is less than the current buffer size
  void setMaxBufferSize(uint32_t maxSize) {
    if (maxSize < bufferSize_) {
      throw TTransportException(TTransportException::BAD_ARGS,
                                "Maximum buffer size would be less than current buffer size");
    }
    maxBufferSize_ = maxSize;
  }

protected:
  void swap(TMemoryBuffer& that) {
    using std::swap;
    swap(buffer_, that.buffer_);
    swap(bufferSize_, that.bufferSize_);

    swap(rBase_, that.rBase_);
    swap(rBound_, that.rBound_);
    swap(wBase_, that.wBase_);
    swap(wBound_, that.wBound_);

    swap(owner_, that.owner_);
  }

  // Make sure there's at least 'len' bytes available for writing.
  void ensureCanWrite(uint32_t len);

  // Compute the position and available data for reading.
  void computeRead(uint32_t len, uint8_t** out_start, uint32_t* out_give);

  uint32_t readSlow(uint8_t* buf, uint32_t len) override;

  void writeSlow(const uint8_t* buf, uint32_t len) override;

  const uint8_t* borrowSlow(uint8_t* buf, uint32_t* len) override;

  // Data buffer
  uint8_t* buffer_;

  // Allocated buffer size
  uint32_t bufferSize_;

  // Maximum allowed size
  uint32_t maxBufferSize_;

  // Is this object the owner of the buffer?
  bool owner_;

  // Don't forget to update constrctors, initCommon, and swap if
  // you add new members.
};
}
}
} // lbug_apache::thrift::transport

#endif // #ifndef _LBUG_THRIFT_TRANSPORT_TBUFFERTRANSPORTS_H_
