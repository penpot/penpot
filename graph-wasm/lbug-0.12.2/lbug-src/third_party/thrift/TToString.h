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

#ifndef _LBUG_THRIFT_TOSTRING_H_
#define _LBUG_THRIFT_TOSTRING_H_ 1

#include <cmath>
#include <limits>
#include <map>
#include <set>
#include <sstream>
#include <string>

namespace lbug_apache {
namespace thrift {

template <typename T>
std::string to_string(const T& t) {
  std::ostringstream o;
  o << t;
  return o.str();
}

// TODO: replace the computations below with std::numeric_limits::max_digits10 once C++11
// is enabled.
inline std::string to_string(const float& t) {
  std::ostringstream o;
  o.precision(static_cast<std::streamsize>(std::ceil(static_cast<double>(std::numeric_limits<float>::digits * std::log10(2.0f) + 1))));
  o << t;
  return o.str();
}

inline std::string to_string(const double& t) {
  std::ostringstream o;
  o.precision(static_cast<std::streamsize>(std::ceil(static_cast<double>(std::numeric_limits<double>::digits * std::log10(2.0f) + 1))));
  o << t;
  return o.str();
}

inline std::string to_string(const long double& t) {
  std::ostringstream o;
  o.precision(static_cast<std::streamsize>(std::ceil(static_cast<double>(std::numeric_limits<long double>::digits * std::log10(2.0f) + 1))));
  o << t;
  return o.str();
}

template <typename K, typename V>
std::string to_string(const std::map<K, V>& m);

template <typename T>
std::string to_string(const std::set<T>& s);

template <typename T>
std::string to_string(const std::vector<T>& t);

template <typename K, typename V>
std::string to_string(const typename std::pair<K, V>& v) {
  std::ostringstream o;
  o << to_string(v.first) << ": " << to_string(v.second);
  return o.str();
}

template <typename T>
std::string to_string(const T& beg, const T& end) {
  std::ostringstream o;
  for (T it = beg; it != end; ++it) {
    if (it != beg)
      o << ", ";
    o << to_string(*it);
  }
  return o.str();
}

template <typename T>
std::string to_string(const std::vector<T>& t) {
  std::ostringstream o;
  o << "[" << to_string(t.begin(), t.end()) << "]";
  return o.str();
}

template <typename K, typename V>
std::string to_string(const std::map<K, V>& m) {
  std::ostringstream o;
  o << "{" << to_string(m.begin(), m.end()) << "}";
  return o.str();
}

template <typename T>
std::string to_string(const std::set<T>& s) {
  std::ostringstream o;
  o << "{" << to_string(s.begin(), s.end()) << "}";
  return o.str();
}
}
} // lbug_apache::thrift

#endif // _LBUG_THRIFT_TOSTRING_H_
