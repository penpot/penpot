#include "processor/operator/persistent/reader/parquet/string_column_reader.h"

#include "common/types/blob.h"
#include "common/types/ku_string.h"
#include "parquet_types.h"
#include "utf8proc_wrapper.h"

using lbug_parquet::format::Type;

namespace lbug {
namespace processor {

StringColumnReader::StringColumnReader(ParquetReader& reader, common::LogicalType type,
    const lbug_parquet::format::SchemaElement& schema, uint64_t schemaIdx, uint64_t maxDefine,
    uint64_t maxRepeat)
    : TemplatedColumnReader<common::ku_string_t, StringParquetValueConversion>(reader,
          std::move(type), schema, schemaIdx, maxDefine, maxRepeat) {
    fixedWidthStringLength = 0;
    if (schema.type == Type::FIXED_LEN_BYTE_ARRAY) {
        KU_ASSERT(schema.__isset.type_length);
        fixedWidthStringLength = schema.type_length;
    }
}

uint32_t StringColumnReader::verifyString(const char* strData, uint32_t strLen,
    const bool isVarchar) {
    if (!isVarchar) {
        return strLen;
    }
    // verify if a string is actually UTF8, and if there are no null bytes in the middle of the
    // string technically Parquet should guarantee this, but reality is often disappointing
    auto reason = utf8proc::UnicodeInvalidReason::INVALID_UNICODE;
    size_t pos = 0;
    auto utf_type = utf8proc::Utf8Proc::analyze(strData, strLen, &reason, &pos);
    if (utf_type == utf8proc::UnicodeType::INVALID) {
        throw common::CopyException{
            "Invalid string encoding found in Parquet file: value \"" +
            common::Blob::toString(reinterpret_cast<const uint8_t*>(strData), strLen) +
            "\" is not valid UTF8!"};
    }
    return strLen;
}

uint32_t StringColumnReader::verifyString(const char* strData, uint32_t strLen) {
    return verifyString(strData, strLen,
        getDataType().getLogicalTypeID() == common::LogicalTypeID::STRING);
}

void StringColumnReader::dictionary(const std::shared_ptr<ResizeableBuffer>& data,
    uint64_t numEntries) {
    dict = data;
    dictStrs = std::unique_ptr<common::ku_string_t[]>(new common::ku_string_t[numEntries]);
    for (auto dictIdx = 0u; dictIdx < numEntries; dictIdx++) {
        auto strLen = fixedWidthStringLength == 0 ? dict->read<uint32_t>() : fixedWidthStringLength;
        dict->available(strLen);

        auto dict_str = reinterpret_cast<const char*>(dict->ptr);
        auto actual_str_len = verifyString(dict_str, strLen);
        dictStrs[dictIdx].setFromRawStr(dict_str, actual_str_len);
        dict->inc(strLen);
    }
}

common::ku_string_t StringParquetValueConversion::dictRead(ByteBuffer& /*dict*/, uint32_t& offset,
    ColumnReader& reader) {
    auto& dictStrings = reinterpret_cast<StringColumnReader&>(reader).dictStrs;
    return dictStrings[offset];
}

common::ku_string_t StringParquetValueConversion::plainRead(ByteBuffer& plainData,
    ColumnReader& reader) {
    auto& scr = reinterpret_cast<StringColumnReader&>(reader);
    uint32_t strLen =
        scr.fixedWidthStringLength == 0 ? plainData.read<uint32_t>() : scr.fixedWidthStringLength;
    plainData.available(strLen);
    auto plainStr = reinterpret_cast<char*>(plainData.ptr);
    auto actualStrLen =
        reinterpret_cast<StringColumnReader&>(reader).verifyString(plainStr, strLen);
    auto retStr = common::ku_string_t();
    retStr.setFromRawStr(plainStr, actualStrLen);
    plainData.inc(strLen);
    return retStr;
}

void StringParquetValueConversion::plainSkip(ByteBuffer& plainData, ColumnReader& reader) {
    auto& scr = reinterpret_cast<StringColumnReader&>(reader);
    uint32_t strLen =
        scr.fixedWidthStringLength == 0 ? plainData.read<uint32_t>() : scr.fixedWidthStringLength;
    plainData.inc(strLen);
}

} // namespace processor
} // namespace lbug
