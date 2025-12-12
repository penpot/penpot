/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "support/StringUtils.h"

namespace antlrcpp {

  std::string escapeWhitespace(std::string_view in) {
    std::string out;
    escapeWhitespace(out, in);
    out.shrink_to_fit();
    return out;
  }

  std::string& escapeWhitespace(std::string& out, std::string_view in) {
    out.reserve(in.size());  // Best case, no escaping.
    for (const auto &c : in) {
      switch (c) {
        case '\t':
          out.append("\\t");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\n':
          out.append("\\n");
          break;
        default:
          out.push_back(c);
          break;
      }
    }
    return out;
  }

} // namespace antrlcpp
