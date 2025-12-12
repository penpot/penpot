/* Copyright (c) 2021 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include <cassert>
#include <cstdint>

#include "support/Utf8.h"
#include "support/Unicode.h"

// The below implementation is based off of https://github.com/google/cel-cpp/internal/utf8.cc,
// which is itself based off of https://go.googlesource.com/go/+/refs/heads/master/src/unicode/utf8/utf8.go.
// If for some reason you feel the need to copy this implementation, please retain a comment
// referencing the two source files and giving credit, as well as maintaining any and all
// obligations required by the BSD 3-clause license that governs this file.

namespace antlrcpp {

namespace {

#undef SELF
  constexpr uint8_t SELF = 0x80;

#undef LOW
  constexpr uint8_t LOW = 0x80;
#undef HIGH
  constexpr uint8_t HIGH = 0xbf;

#undef MASKX
  constexpr uint8_t MASKX = 0x3f;
#undef MASK2
  constexpr uint8_t MASK2 = 0x1f;
#undef MASK3
  constexpr uint8_t MASK3 = 0xf;
#undef MASK4
  constexpr uint8_t MASK4 = 0x7;

#undef TX
  constexpr uint8_t TX = 0x80;
#undef T2
  constexpr uint8_t T2 = 0xc0;
#undef T3
  constexpr uint8_t T3 = 0xe0;
#undef T4
  constexpr uint8_t T4 = 0xf0;

#undef XX
  constexpr uint8_t XX = 0xf1;
#undef AS
  constexpr uint8_t AS = 0xf0;
#undef S1
  constexpr uint8_t S1 = 0x02;
#undef S2
  constexpr uint8_t S2 = 0x13;
#undef S3
  constexpr uint8_t S3 = 0x03;
#undef S4
  constexpr uint8_t S4 = 0x23;
#undef S5
  constexpr uint8_t S5 = 0x34;
#undef S6
  constexpr uint8_t S6 = 0x04;
#undef S7
  constexpr uint8_t S7 = 0x44;

  // NOLINTBEGIN
  // clang-format off
#undef LEADING
  constexpr uint8_t LEADING[256] = {
    //   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x00-0x0F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x10-0x1F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x20-0x2F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x30-0x3F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x40-0x4F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x50-0x5F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x60-0x6F
    AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, AS, // 0x70-0x7F
    //   1   2   3   4   5   6   7   8   9   A   B   C   D   E   F
    XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, // 0x80-0x8F
    XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, // 0x90-0x9F
    XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, // 0xA0-0xAF
    XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, // 0xB0-0xBF
    XX, XX, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, // 0xC0-0xCF
    S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, S1, // 0xD0-0xDF
    S2, S3, S3, S3, S3, S3, S3, S3, S3, S3, S3, S3, S3, S4, S3, S3, // 0xE0-0xEF
    S5, S6, S6, S6, S7, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, XX, // 0xF0-0xFF
  };
  // clang-format on
  // NOLINTEND

#undef ACCEPT
  constexpr std::pair<uint8_t, uint8_t> ACCEPT[16] = {
      {LOW, HIGH}, {0xa0, HIGH}, {LOW, 0x9f}, {0x90, HIGH},
      {LOW, 0x8f}, {0x0, 0x0},   {0x0, 0x0},  {0x0, 0x0},
      {0x0, 0x0},  {0x0, 0x0},   {0x0, 0x0},  {0x0, 0x0},
      {0x0, 0x0},  {0x0, 0x0},   {0x0, 0x0},  {0x0, 0x0},
  };

}  // namespace

  std::pair<char32_t, size_t> Utf8::decode(std::string_view input) {
    assert(!input.empty());
    const auto b = static_cast<uint8_t>(input.front());
    input.remove_prefix(1);
    if (b < SELF) {
      return {static_cast<char32_t>(b), 1};
    }
    const auto leading = LEADING[b];
    if (leading == XX) {
      return {Unicode::REPLACEMENT_CHARACTER, 1};
    }
    auto size = static_cast<size_t>(leading & 7) - 1;
    if (size > input.size()) {
      return {Unicode::REPLACEMENT_CHARACTER, 1};
    }
    const auto& accept = ACCEPT[leading >> 4];
    const auto b1 = static_cast<uint8_t>(input.front());
    input.remove_prefix(1);
    if (b1 < accept.first || b1 > accept.second) {
      return {Unicode::REPLACEMENT_CHARACTER, 1};
    }
    if (size <= 1) {
      return {(static_cast<char32_t>(b & MASK2) << 6) |
                  static_cast<char32_t>(b1 & MASKX),
              2};
    }
    const auto b2 = static_cast<uint8_t>(input.front());
    input.remove_prefix(1);
    if (b2 < LOW || b2 > HIGH) {
      return {Unicode::REPLACEMENT_CHARACTER, 1};
    }
    if (size <= 2) {
      return {(static_cast<char32_t>(b & MASK3) << 12) |
                  (static_cast<char32_t>(b1 & MASKX) << 6) |
                  static_cast<char32_t>(b2 & MASKX),
              3};
    }
    const auto b3 = static_cast<uint8_t>(input.front());
    input.remove_prefix(1);
    if (b3 < LOW || b3 > HIGH) {
      return {Unicode::REPLACEMENT_CHARACTER, 1};
    }
    return {(static_cast<char32_t>(b & MASK4) << 18) |
                (static_cast<char32_t>(b1 & MASKX) << 12) |
                (static_cast<char32_t>(b2 & MASKX) << 6) |
                static_cast<char32_t>(b3 & MASKX),
            4};
  }

  std::optional<std::u32string> Utf8::strictDecode(std::string_view input) {
    std::u32string output;
    char32_t codePoint;
    size_t codeUnits;
    output.reserve(input.size());  // Worst case is each byte is a single Unicode code point.
    for (size_t index = 0; index < input.size(); index += codeUnits) {
      std::tie(codePoint, codeUnits) = Utf8::decode(input.substr(index));
      if (codePoint == Unicode::REPLACEMENT_CHARACTER && codeUnits == 1) {
        // Condition is only met when an illegal byte sequence is encountered. See Utf8::decode.
        return std::nullopt;
      }
      output.push_back(codePoint);
    }
    output.shrink_to_fit();
    return output;
  }

  std::u32string Utf8::lenientDecode(std::string_view input) {
    std::u32string output;
    char32_t codePoint;
    size_t codeUnits;
    output.reserve(input.size());  // Worst case is each byte is a single Unicode code point.
    for (size_t index = 0; index < input.size(); index += codeUnits) {
      std::tie(codePoint, codeUnits) = Utf8::decode(input.substr(index));
      output.push_back(codePoint);
    }
    output.shrink_to_fit();
    return output;
  }

  std::string& Utf8::encode(std::string* buffer, char32_t codePoint) {
    assert(buffer != nullptr);
    if (!Unicode::isValid(codePoint)) {
      codePoint = Unicode::REPLACEMENT_CHARACTER;
    }
    if (codePoint <= 0x7f) {
      buffer->push_back(static_cast<char>(static_cast<uint8_t>(codePoint)));
    } else if (codePoint <= 0x7ff) {
      buffer->push_back(
          static_cast<char>(T2 | static_cast<uint8_t>(codePoint >> 6)));
      buffer->push_back(
          static_cast<char>(TX | (static_cast<uint8_t>(codePoint) & MASKX)));
    } else if (codePoint <= 0xffff) {
      buffer->push_back(
          static_cast<char>(T3 | static_cast<uint8_t>(codePoint >> 12)));
      buffer->push_back(static_cast<char>(
          TX | (static_cast<uint8_t>(codePoint >> 6) & MASKX)));
      buffer->push_back(
          static_cast<char>(TX | (static_cast<uint8_t>(codePoint) & MASKX)));
    } else {
      buffer->push_back(
          static_cast<char>(T4 | static_cast<uint8_t>(codePoint >> 18)));
      buffer->push_back(static_cast<char>(
          TX | (static_cast<uint8_t>(codePoint >> 12) & MASKX)));
      buffer->push_back(static_cast<char>(
          TX | (static_cast<uint8_t>(codePoint >> 6) & MASKX)));
      buffer->push_back(
          static_cast<char>(TX | (static_cast<uint8_t>(codePoint) & MASKX)));
    }
    return *buffer;
  }

  std::optional<std::string> Utf8::strictEncode(std::u32string_view input) {
    std::string output;
    output.reserve(input.size() * 4);  // Worst case is each Unicode code point encodes to 4 bytes.
    for (size_t index = 0; index < input.size(); index++) {
      char32_t codePoint = input[index];
      if (!Unicode::isValid(codePoint)) {
        return std::nullopt;
      }
      Utf8::encode(&output, codePoint);
    }
    output.shrink_to_fit();
    return output;
  }

  std::string Utf8::lenientEncode(std::u32string_view input) {
    std::string output;
    output.reserve(input.size() * 4);  // Worst case is each Unicode code point encodes to 4 bytes.
    for (size_t index = 0; index < input.size(); index++) {
      char32_t codePoint = input[index];
      if (!Unicode::isValid(codePoint)) {
        codePoint = Unicode::REPLACEMENT_CHARACTER;
      }
      Utf8::encode(&output, codePoint);
    }
    output.shrink_to_fit();
    return output;
  }

}
