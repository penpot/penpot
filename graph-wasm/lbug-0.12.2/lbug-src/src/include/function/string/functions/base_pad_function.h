#pragma once

#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"
#include "utf8proc.h"

namespace lbug {
namespace function {

// Padding logic has been taken from DuckDB:
// https://github.com/duckdb/duckdb/blob/master/src/function/scalar/string/pad.cpp
struct BasePadOperation {
public:
    static inline void operation(common::ku_string_t& src, int64_t count,
        common::ku_string_t& characterToPad, common::ku_string_t& result,
        common::ValueVector& resultValueVector,
        void (*padOperation)(common::ku_string_t& src, int64_t count,
            common::ku_string_t& characterToPad, std::string& paddedResult)) {
        if (count < 0) {
            count = 0;
        }
        std::string paddedResult;
        padOperation(src, count, characterToPad, paddedResult);
        common::StringVector::addString(&resultValueVector, result, paddedResult.data(),
            paddedResult.size());
    }

    static std::pair<uint32_t, uint32_t> padCountChars(const uint32_t count, const char* data,
        const uint32_t size) {
        auto str = reinterpret_cast<const utf8proc::utf8proc_uint8_t*>(data);
        uint32_t byteCount = 0, charCount = 0;
        for (; charCount < count && byteCount < size; charCount++) {
            utf8proc::utf8proc_int32_t codepoint = 0;
            auto bytes = utf8proc::utf8proc_iterate(str + byteCount, size - byteCount, &codepoint);
            byteCount += bytes;
        }
        return {byteCount, charCount};
    }

    static void insertPadding(uint32_t charCount, common::ku_string_t pad, std::string& result) {
        auto padData = pad.getData();
        auto padSize = pad.len;
        uint32_t padByteCount = 0;
        for (auto i = 0u; i < charCount; i++) {
            if (padByteCount >= padSize) {
                result.insert(result.end(), (char*)padData, (char*)(padData + padByteCount));
                padByteCount = 0;
            }
            utf8proc::utf8proc_int32_t codepoint = 0;
            auto bytes = utf8proc::utf8proc_iterate(padData + padByteCount, padSize - padByteCount,
                &codepoint);
            padByteCount += bytes;
        }
        result.insert(result.end(), (char*)padData, (char*)(padData + padByteCount));
    }
};

} // namespace function
} // namespace lbug
