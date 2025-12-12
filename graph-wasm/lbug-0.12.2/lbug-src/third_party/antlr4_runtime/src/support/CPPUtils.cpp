/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "support/CPPUtils.h"

namespace antlrcpp {

  std::string join(const std::vector<std::string> &strings, const std::string &separator) {
    std::string str;
    bool firstItem = true;
    for (const std::string &s : strings) {
      if (!firstItem) {
        str.append(separator);
      }
      firstItem = false;
      str.append(s);
    }
    return str;
  }

  std::map<std::string, size_t> toMap(const std::vector<std::string> &keys) {
    std::map<std::string, size_t> result;
    for (size_t i = 0; i < keys.size(); ++i) {
      result.insert({ keys[i], i });
    }
    return result;
  }

  std::string escapeWhitespace(std::string str, bool escapeSpaces) {
    std::string result;
    for (auto c : str) {
      switch (c) {
        case '\n':
          result += "\\n";
          break;

        case '\r':
          result += "\\r";
          break;

        case '\t':
          result += "\\t";
          break;

        case ' ':
          if (escapeSpaces) {
            result += "\u00B7";
            break;
          }
          result += c;
          break;

        default:
          result += c;
          break;
      }
    }

    return result;
  }

  std::string toHexString(const int t) {
    std::stringstream stream;
    stream << std::uppercase << std::hex << t;
    return stream.str();
  }

  std::string arrayToString(const std::vector<std::string> &data) {
    std::string answer;
    size_t toReserve = 0;
    for (const auto &sub : data) {
      toReserve += sub.size();
    }
    answer.reserve(toReserve);
    for (const auto &sub: data) {
      answer.append(sub);
    }
    return answer;
  }

  std::string replaceString(const std::string &s, const std::string &from, const std::string &to) {
    std::string::size_type p;
    std::string ss, res;

    ss = s;
    p = ss.find(from);
    while (p != std::string::npos) {
      if (p > 0)
        res.append(ss.substr(0, p)).append(to);
      else
        res.append(to);
      ss = ss.substr(p + from.size());
      p = ss.find(from);
    }
    res.append(ss);

    return res;
  }

  std::vector<std::string> split(const std::string &s, const std::string &sep, int count) {
    std::vector<std::string> parts;
    std::string ss = s;

    std::string::size_type p;

    if (s.empty())
      return parts;

    if (count == 0)
      count= -1;

    p = ss.find(sep);
    while (!ss.empty() && p != std::string::npos && (count < 0 || count > 0)) {
      parts.push_back(ss.substr(0, p));
      ss = ss.substr(p+sep.size());

      --count;
      p = ss.find(sep);
    }
    parts.push_back(ss);

    return parts;
  }

  //--------------------------------------------------------------------------------------------------

  // Debugging helper. Adds indentation to all lines in the given string.
  std::string indent(const std::string &s, const std::string &indentation, bool includingFirst) {
    std::vector<std::string> parts = split(s, "\n", -1);
    for (size_t i = 0; i < parts.size(); ++i) {
      if (i == 0 && !includingFirst)
        continue;
      parts[i].insert(0, indentation);
    }

    return join(parts, "\n");
  }

  //--------------------------------------------------------------------------------------------------

  // Recursively get the error from a, possibly nested, exception.
#if defined(_MSC_FULL_VER) && _MSC_FULL_VER < 190023026
  // No nested exceptions before VS 2015.
  template <typename T>
  std::exception_ptr get_nested(const T &/*e*/) {
    try {
      return nullptr;
    }
    catch (const std::bad_cast &) {
      return nullptr;
    }
  }
#else
  template <typename T>
  std::exception_ptr get_nested(const T &e) {
    try {
      auto nested = dynamic_cast<const std::nested_exception&>(e);
      return nested.nested_ptr();
    }
    catch (const std::bad_cast &) {
      return nullptr;
    }
  }
#endif

  std::string what(std::exception_ptr eptr) {
    if (!eptr) {
      throw std::bad_exception();
    }

    std::string result;
    std::size_t nestCount = 0;

    next: {
      try {
        std::exception_ptr yeptr;
        std::swap(eptr, yeptr);
        std::rethrow_exception(yeptr);
      }
      catch (const std::exception &e) {
        result += e.what();
        eptr = get_nested(e);
      }
      catch (const std::string &e) {
        result += e;
      }
      catch (const char *e) {
        result += e;
      }
      catch (...) {
        result += "cannot be determined";
      }

      if (eptr) {
        result += " (";
        ++nestCount;
        goto next;
      }
    }

    result += std::string(nestCount, ')');
    return result;
  }

} // namespace antlrcpp
