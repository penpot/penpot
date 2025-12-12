#pragma once

#include <regex>
#include <string>
#include <vector>

#include "common/api.h"
#include "function/cast/functions/numeric_limits.h"
#include <span>

namespace lbug {
namespace common {

class LBUG_API StringUtils {
public:
    static std::vector<std::string> splitComma(const std::string& input);

    // Does not split within [], {}, or ().
    // can specify maximum number of elements to split
    static std::vector<std::string_view> smartSplit(std::string_view input, char splitChar,
        uint64_t maxNumEle = function::NumericLimits<uint64_t>::maximum());

    static std::vector<std::string> split(const std::string& input, const std::string& delimiter,
        bool ignoreEmptyStringParts = true);
    static std::vector<std::string> splitBySpace(const std::string& input);

    static std::string getUpper(const std::string& input);
    static std::string getUpper(const std::string_view& input);
    static std::string getLower(const std::string& input);
    static void toLower(std::string& input);
    static void toUpper(std::string& input);

    static bool isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\v' || c == '\f' || c == '\r';
    }
    static bool characterIsNewLine(char c) { return c == '\n' || c == '\r'; }
    static bool CharacterIsDigit(char c) { return c >= '0' && c <= '9'; }

    static std::string ltrim(const std::string& input) {
        auto s = input;
        s.erase(s.begin(),
            find_if(s.begin(), s.end(), [](unsigned char ch) { return !isspace(ch); }));
        return s;
    }
    static std::string_view ltrim(std::string_view input) {
        auto begin = 0u;
        while (begin < input.size() && isspace(input[begin])) {
            begin++;
        }
        return input.substr(begin);
    }
    static std::string rtrim(const std::string& input) {
        auto s = input;
        s.erase(find_if(s.rbegin(), s.rend(), [](unsigned char ch) { return !isspace(ch); }).base(),
            s.end());
        return s;
    }
    static std::string_view rtrim(std::string_view input) {
        auto end = input.size();
        while (end > 0 && isSpace(input[end - 1])) {
            end--;
        }
        return input.substr(0, end);
    }
    static std::string ltrimNewlines(const std::string& input);
    static std::string rtrimNewlines(const std::string& input);

    static void removeWhiteSpaces(std::string& str) {
        std::regex whiteSpacePattern{"\\s"};
        str = std::regex_replace(str, whiteSpacePattern, "");
    }

    static void removeCStringWhiteSpaces(const char*& input, uint64_t& len);

    static void replaceAll(std::string& str, const std::string& search,
        const std::string& replacement);

    static std::string extractStringBetween(const std::string& input, char delimiterStart,
        char delimiterEnd, bool includeDelimiter = false);

    static uint64_t caseInsensitiveHash(const std::string& str);

    static bool caseInsensitiveEquals(std::string_view left, std::string_view right);

    // join multiple strings into one string. Components are concatenated by the given separator
    static std::string join(const std::vector<std::string>& input, const std::string& separator);
    static std::string join(const std::span<const std::string_view> input,
        const std::string& separator);

    // join multiple items of container with given size, transformed to string
    // using function, into one string using the given separator
    template<typename C, typename S, typename Func>
    static std::string join(const C& input, S count, const std::string& separator, Func f);

    static constexpr uint8_t asciiToLowerCaseMap[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
        37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
        60, 61, 62, 63, 64, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111,
        112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 91, 92, 93, 94, 95, 96, 97, 98, 99,
        100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117,
        118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135,
        136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153,
        154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171,
        172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189,
        190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207,
        208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225,
        226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243,
        244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255};

    static std::string encodeURL(const std::string& input, bool encodeSlash = false);

    // container hash function for strings which lets you hash both string_view and string
    // references
    struct string_hash {
        using hash_type = std::hash<std::string_view>;
        using is_transparent = void;

        std::size_t operator()(const char* str) const { return hash_type{}(str); }
        std::size_t operator()(std::string_view str) const { return hash_type{}(str); }
        std::size_t operator()(std::string const& str) const { return hash_type{}(str); }
    };
};

} // namespace common
} // namespace lbug
