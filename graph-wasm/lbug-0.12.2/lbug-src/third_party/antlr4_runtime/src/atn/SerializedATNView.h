/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <vector>

#include "antlr4-common.h"
#include "misc/MurmurHash.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC SerializedATNView final {
  public:
    using value_type = int32_t;
    using size_type = size_t;
    using difference_type = ptrdiff_t;
    using reference = int32_t&;
    using const_reference = const int32_t&;
    using pointer = int32_t*;
    using const_pointer = const int32_t*;
    using iterator = const_pointer;
    using const_iterator = const_pointer;
    using reverse_iterator = std::reverse_iterator<iterator>;
    using const_reverse_iterator = std::reverse_iterator<const_iterator>;

    SerializedATNView() = default;

    SerializedATNView(const_pointer data, size_type size) : _data(data), _size(size) {}

    SerializedATNView(const std::vector<int32_t> &serializedATN) : _data(serializedATN.data()), _size(serializedATN.size()) {}

    SerializedATNView(const SerializedATNView&) = default;

    SerializedATNView& operator=(const SerializedATNView&) = default;

    const_iterator begin() const { return data(); }

    const_iterator cbegin() const { return data(); }

    const_iterator end() const { return data() + size(); }

    const_iterator cend() const { return data() + size(); }

    const_reverse_iterator rbegin() const { return const_reverse_iterator(end()); }

    const_reverse_iterator crbegin() const { return const_reverse_iterator(cend()); }

    const_reverse_iterator rend() const { return const_reverse_iterator(begin()); }

    const_reverse_iterator crend() const { return const_reverse_iterator(cbegin()); }

    bool empty() const { return size() == 0; }

    const_pointer data() const { return _data; }

    size_type size() const { return _size; }

    size_type size_bytes() const { return size() * sizeof(value_type); }

    const_reference operator[](size_type index) const { return _data[index]; }

  private:
    const_pointer _data = nullptr;
    size_type _size = 0;
  };

  inline bool operator==(const SerializedATNView &lhs, const SerializedATNView &rhs) {
    return (lhs.data() == rhs.data() && lhs.size() == rhs.size()) ||
           (lhs.size() == rhs.size() && std::memcmp(lhs.data(), rhs.data(), lhs.size_bytes()) == 0);
  }

  inline bool operator!=(const SerializedATNView &lhs, const SerializedATNView &rhs) {
    return !operator==(lhs, rhs);
  }

  inline bool operator<(const SerializedATNView &lhs, const SerializedATNView &rhs) {
    int diff = std::memcmp(lhs.data(), rhs.data(), std::min(lhs.size_bytes(), rhs.size_bytes()));
    return diff < 0 || (diff == 0 && lhs.size() < rhs.size());
  }

}  // namespace atn
}  // namespace antlr4

namespace std {

  template <>
  struct hash<::antlr4::atn::SerializedATNView> {
    size_t operator()(const ::antlr4::atn::SerializedATNView &serializedATNView) const {
      return ::antlr4::misc::MurmurHash::hashCode(serializedATNView.data(), serializedATNView.size());
    }
  };

}  // namespace std
