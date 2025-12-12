#include "processor/operator/persistent/reader/parquet/boolean_column_reader.h"

namespace lbug {
namespace processor {

void BooleanColumnReader::initializeRead(uint64_t rowGroupIdx,
    const std::vector<lbug_parquet::format::ColumnChunk>& columns,
    lbug_apache::thrift::protocol::TProtocol& protocol) {
    bytePos = 0;
    TemplatedColumnReader<bool, BooleanParquetValueConversion>::initializeRead(rowGroupIdx, columns,
        protocol);
}

bool BooleanParquetValueConversion::plainRead(ByteBuffer& plainData, ColumnReader& reader) {
    plainData.available(1);
    auto& bytePos = reinterpret_cast<BooleanColumnReader&>(reader).bytePos;
    bool ret = (*plainData.ptr >> bytePos) & 1;
    bytePos++;
    if (bytePos == 8) {
        bytePos = 0;
        plainData.inc(1);
    }
    return ret;
}

} // namespace processor
} // namespace lbug
