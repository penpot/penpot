#include "common/string_utils.h"

#include <sstream>
#include <vector>

#include "common/exception/runtime.h"
#include "function/string/functions/base_lower_upper_function.h"
#include "utf8proc_wrapper.h"

namespace lbug {
namespace common {

std::vector<std::string> StringUtils::splitComma(const std::string& input) {
    auto result = std::vector<std::string>();
    auto currentPos = 0u;
    auto lvl = 0u;
    while (currentPos < input.length()) {
        if (input[currentPos] == '(') {
            lvl++;
        } else if (input[currentPos] == ')') {
            lvl--;
        } else if (lvl == 0 && input[currentPos] == ',') {
            break;
        }
        currentPos++;
    }
    result.push_back(input.substr(0, currentPos));
    result.push_back(input.substr(currentPos == input.length() ? input.length() : currentPos + 1));
    return result;
}

static char openingBracket(char c) {
    if (c == ')') {
        return '(';
    }
    if (c == ']') {
        return '[';
    }
    if (c == '}') {
        return '{';
    }
    return c;
}

std::vector<std::string_view> StringUtils::smartSplit(std::string_view input, char splitChar,
    uint64_t maxNumEle) {
    if (input.size() == 0) {
        return {};
    }
    std::vector<std::string_view> result;
    auto currentItem = 0u;
    std::vector<char> stk;
    bool insideSingleQuote = false;
    for (auto i = 0u; i < input.size(); i++) {
        char c = input[i];

        if (c == '\'' && (stk.size() == 0 || stk.back() != '\'')) {
            // Entering/Exiting a single-quoted block.
            insideSingleQuote = !insideSingleQuote;
        } else if (c == splitChar && stk.size() == 0u && !insideSingleQuote) {
            if (result.size() + 1 == maxNumEle) {
                result.push_back(input.substr(currentItem));
                return result;
            } else {
                result.push_back(input.substr(currentItem, i - currentItem));
                currentItem = i + 1;
            }
        } else if (c == '{' || c == '(' || c == '[' ||
                   (c == '\"' && (stk.size() == 0u || stk.back() != '\"'))) {
            stk.push_back(c);
        } else if (stk.size() > 0 && openingBracket(c) == stk.back()) {
            stk.pop_back();
        }
    }
    result.push_back(input.substr(currentItem));
    return result;
}

uint64_t findDelim(const std::string& input, const std::string& delimiter, uint64_t prevPos) {
    if (delimiter != "") {
        return input.find(delimiter, prevPos);
    }
    return prevPos < input.size() - 1 ? prevPos + 1 : std::string::npos;
}

std::vector<std::string> StringUtils::split(const std::string& input, const std::string& delimiter,
    bool ignoreEmptyStringParts) {
    auto result = std::vector<std::string>();
    auto prevPos = 0u;
    auto currentPos = findDelim(input, delimiter, prevPos);
    while (currentPos != std::string::npos) {
        auto stringPart = input.substr(prevPos, currentPos - prevPos);
        if (!ignoreEmptyStringParts || !stringPart.empty()) {
            result.push_back(input.substr(prevPos, currentPos - prevPos));
        }
        prevPos = currentPos + delimiter.size();
        currentPos = findDelim(input, delimiter, prevPos);
    }
    result.push_back(input.substr(prevPos));
    return result;
}

std::vector<std::string> StringUtils::splitBySpace(const std::string& input) {
    std::istringstream iss(input);
    std::vector<std::string> result;
    std::string token;
    while (iss >> token) {
        result.push_back(token);
    }
    return result;
}

std::string StringUtils::getUpper(const std::string& input) {
    auto result = input;
    toUpper(result);
    return result;
}

std::string StringUtils::getUpper(const std::string_view& input) {
    auto result = std::string(input);
    toUpper(result);
    return result;
}

std::string StringUtils::getLower(const std::string& input) {
    auto result = input;
    toLower(result);
    return result;
}

template<bool toUpper>
static void changeCase(std::string& input) {
    if (!utf8proc::Utf8Proc::isValid(input.c_str(), input.length())) {
        throw RuntimeException{"Invalid UTF8-encoded string."};
    }
    auto resultLen = function::BaseLowerUpperFunction::getResultLen((char*)input.data(),
        input.length(), toUpper);
    std::string result(resultLen, '\0' /* char */);
    function::BaseLowerUpperFunction::convertCase((char*)result.data(), input.length(),
        input.data(), toUpper);
    input = result;
}

void StringUtils::toLower(std::string& input) {
    changeCase<false>(input);
}

void StringUtils::toUpper(std::string& input) {
    changeCase<true>(input);
}

void StringUtils::removeCStringWhiteSpaces(const char*& input, uint64_t& len) {
    // skip leading/trailing spaces
    while (len > 0 && isspace(input[0])) {
        input++;
        len--;
    }
    while (len > 0 && isspace(input[len - 1])) {
        len--;
    }
}

void StringUtils::replaceAll(std::string& str, const std::string& search,
    const std::string& replacement) {
    size_t pos = 0;
    while ((pos = str.find(search, pos)) != std::string::npos) {
        str.replace(pos, search.length(), replacement);
        pos += replacement.length();
    }
}

std::string StringUtils::extractStringBetween(const std::string& input, char delimiterStart,
    char delimiterEnd, bool includeDelimiter) {
    std::string::size_type posStart = input.find_first_of(delimiterStart);
    std::string::size_type posEnd = input.find_last_of(delimiterEnd);
    if (posStart == std::string::npos || posEnd == std::string::npos || posStart >= posEnd) {
        return "";
    }
    if (includeDelimiter) {
        posEnd++;
    } else {
        posStart++;
    }
    return input.substr(posStart, posEnd - posStart);
}

// Jenkins hash function: https://en.wikipedia.org/wiki/Jenkins_hash_function.
// We transform each character to its lower case and apply one_at_a_time hash.
uint64_t StringUtils::caseInsensitiveHash(const std::string& str) {
    uint32_t hash = 0;
    for (auto c : str) {
        hash += tolower(c);
        hash += hash << 10;
        hash ^= hash >> 6;
    }
    hash += hash << 3;
    hash ^= hash >> 11;
    hash += hash << 15;
    return hash;
}

bool StringUtils::caseInsensitiveEquals(std::string_view left, std::string_view right) {
    if (left.size() != right.size()) {
        return false;
    }
    for (auto c = 0u; c < left.size(); c++) {
        if (asciiToLowerCaseMap[(uint8_t)left[c]] != asciiToLowerCaseMap[(uint8_t)right[c]]) {
            return false;
        }
    }
    return true;
}

std::string StringUtils::join(const std::vector<std::string>& input, const std::string& separator) {
    return StringUtils::join(input, input.size(), separator,
        [](const std::string& s) { return s; });
}

std::string StringUtils::join(const std::span<const std::string_view> input,
    const std::string& separator) {
    return StringUtils::join(input, input.size(), separator,
        [](const std::string_view s) { return std::string{s}; });
}

template<typename C, typename S, typename Func>
std::string StringUtils::join(const C& input, S count, const std::string& separator, Func f) {
    std::string result;
    // if the input isn't empty, append the first element. We do this so we
    // don't need to introduce an if into the loop.
    if (count > 0) {
        result += f(input[0]);
    }
    // append the remaining input components, after the first
    for (size_t i = 1; i < count; i++) {
        result += separator + f(input[i]);
    }
    return result;
}

std::string StringUtils::ltrimNewlines(const std::string& input) {
    auto s = input;
    s.erase(s.begin(),
        find_if(s.begin(), s.end(), [](unsigned char ch) { return !characterIsNewLine(ch); }));
    return s;
}

std::string StringUtils::rtrimNewlines(const std::string& input) {
    auto s = input;
    s.erase(find_if(s.rbegin(), s.rend(), [](unsigned char ch) { return !characterIsNewLine(ch); })
                .base(),
        s.end());
    return s;
}

std::string StringUtils::encodeURL(const std::string& input, bool encodeSlash) {
    static const char* hex_digit = "0123456789ABCDEF";
    static constexpr std::string_view unreserved_chars = "_.-~";
    constexpr auto isUnreserved = [](char ch) {
        return std::isalnum(ch) || unreserved_chars.find(ch) != std::string_view::npos;
    };
    std::string result;
    result.reserve(input.size());
    for (auto ch : input) {
        if (isUnreserved(ch)) {
            result += ch;
        } else if (ch == '/') {
            if (encodeSlash) {
                result += std::string("%2F");
            } else {
                result += ch;
            }
        } else {
            result += std::string("%");
            result += hex_digit[static_cast<unsigned char>(ch) >> 4];
            result += hex_digit[static_cast<unsigned char>(ch) & 15];
        }
    }
    return result;
}
} // namespace common
} // namespace lbug
