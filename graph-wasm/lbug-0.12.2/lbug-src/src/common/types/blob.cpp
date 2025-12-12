#include "common/types/blob.h"

#include "common/exception/conversion.h"
#include "common/string_format.h"

namespace lbug {
namespace common {

const int HexFormatConstants::HEX_MAP[256] = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -1, -1, -1, -1,
    -1, -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1};

static bool isRegularChar(char c) {
    return c >= 32 && c <= 126 && c != '\\' && c != '\'' && c != '"';
}

uint64_t Blob::getBlobSize(const ku_string_t& blob) {
    uint64_t blobSize = 0;
    auto length = blob.len;
    auto blobStr = blob.getData();
    for (auto i = 0u; i < length; i++) {
        if (blobStr[i] == '\\') {
            validateHexCode(blobStr, length, i);
            blobSize++;
            i += HexFormatConstants::LENGTH - 1;
        } else if (blobStr[i] <= 127) {
            blobSize++;
        } else {
            throw ConversionException(
                "Invalid byte encountered in STRING -> BLOB conversion. All non-ascii characters "
                "must be escaped with hex codes (e.g. \\xAA)");
        }
    }
    return blobSize;
}

uint64_t Blob::fromString(const char* str, uint64_t length, uint8_t* resultBuffer) {
    auto resultPos = 0u;
    for (auto i = 0u; i < length; i++) {
        if (str[i] == '\\') {
            validateHexCode(reinterpret_cast<const uint8_t*>(str), length, i);
            auto firstByte = HexFormatConstants::HEX_MAP[(
                unsigned char)str[i + HexFormatConstants::FIRST_BYTE_POS]];
            auto secondByte = HexFormatConstants::HEX_MAP[(
                unsigned char)str[i + HexFormatConstants::SECOND_BYTES_POS]];
            resultBuffer[resultPos++] =
                (firstByte << HexFormatConstants::NUM_BYTES_TO_SHIFT_FOR_FIRST_BYTE) + secondByte;
            i += HexFormatConstants::LENGTH - 1;
        } else {
            resultBuffer[resultPos++] = str[i];
        }
    }
    return resultPos;
}

std::string Blob::toString(const uint8_t* value, uint64_t len) {
    std::string result;
    for (auto i = 0u; i < len; i++) {
        if (isRegularChar(value[i])) {
            // ascii characters are rendered as-is.
            result += value[i];
        } else {
            auto firstByte = value[i] >> HexFormatConstants::NUM_BYTES_TO_SHIFT_FOR_FIRST_BYTE;
            auto secondByte = value[i] & HexFormatConstants::SECOND_BYTE_MASK;
            // non-ascii characters are rendered as hexadecimal (e.g. \x00).
            result += '\\';
            result += 'x';
            result += HexFormatConstants::HEX_TABLE[firstByte];
            result += HexFormatConstants::HEX_TABLE[secondByte];
        }
    }
    return result;
}

void Blob::validateHexCode(const uint8_t* blobStr, uint64_t length, uint64_t curPos) {
    if (curPos + HexFormatConstants::LENGTH > length) {
        throw ConversionException(
            "Invalid hex escape code encountered in string -> blob conversion: "
            "unterminated escape code at end of string");
    }
    if (memcmp(blobStr + curPos, HexFormatConstants::PREFIX, HexFormatConstants::PREFIX_LENGTH) !=
            0 ||
        HexFormatConstants::HEX_MAP[blobStr[curPos + HexFormatConstants::FIRST_BYTE_POS]] < 0 ||
        HexFormatConstants::HEX_MAP[blobStr[curPos + HexFormatConstants::SECOND_BYTES_POS]] < 0) {
        throw ConversionException(
            stringFormat("Invalid hex escape code encountered in string -> blob conversion: {}",
                std::string((char*)blobStr + curPos, HexFormatConstants::LENGTH)));
    }
}

} // namespace common
} // namespace lbug
