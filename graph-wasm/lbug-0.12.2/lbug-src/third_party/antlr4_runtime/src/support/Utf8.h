/* Copyright (c) 2021 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <tuple>

#include "antlr4-common.h"

namespace antlrcpp {

  class ANTLR4CPP_PUBLIC Utf8 final {
  public:
    // Decodes the next code point, returning the decoded code point and the number
    // of code units (a.k.a. bytes) consumed. In the event that an invalid code unit
    // sequence is returned the replacement character, U+FFFD, is returned with a
    // code unit count of 1. As U+FFFD requires 3 code units when encoded, this can
    // be used to differentiate valid input from malformed input.
    static std::pair<char32_t, size_t> decode(std::string_view input);

    // Decodes the given UTF-8 encoded input into a string of code points.
    static std::optional<std::u32string> strictDecode(std::string_view input);

    // Decodes the given UTF-8 encoded input into a string of code points. Unlike strictDecode(),
    // each byte in an illegal byte sequence is replaced with the Unicode replacement character,
    // U+FFFD.
    static std::u32string lenientDecode(std::string_view input);

    // Encodes the given code point and appends it to the buffer. If the code point
    // is an unpaired surrogate or outside of the valid Unicode range it is replaced
    // with the replacement character, U+FFFD.
    static std::string& encode(std::string *buffer, char32_t codePoint);

    // Encodes the given Unicode code point string as UTF-8.
    static std::optional<std::string> strictEncode(std::u32string_view input);

    // Encodes the given Unicode code point string as UTF-8. Unlike strictEncode(),
    // each invalid Unicode code point is replaced with the Unicode replacement character, U+FFFD.
    static std::string lenientEncode(std::u32string_view input);

  private:
    Utf8() = delete;
    Utf8(const Utf8&) = delete;
    Utf8(Utf8&&) = delete;
    Utf8& operator=(const Utf8&) = delete;
    Utf8& operator=(Utf8&&) = delete;
  };

}
