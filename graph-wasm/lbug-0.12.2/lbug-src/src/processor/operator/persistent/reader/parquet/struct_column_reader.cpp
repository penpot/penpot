#include "processor/operator/persistent/reader/parquet/struct_column_reader.h"

namespace lbug {
namespace processor {

StructColumnReader::StructColumnReader(ParquetReader& reader, common::LogicalType type,
    const lbug_parquet::format::SchemaElement& schema, uint64_t schemaIdx, uint64_t maxDefine,
    uint64_t maxRepeat, std::vector<std::unique_ptr<ColumnReader>> childReaders)
    : ColumnReader(reader, std::move(type), schema, schemaIdx, maxDefine, maxRepeat),
      childReaders(std::move(childReaders)) {
    KU_ASSERT(this->type.getPhysicalType() == common::PhysicalTypeID::STRUCT);
}

ColumnReader* StructColumnReader::getChildReader(uint64_t childIdx) {
    KU_ASSERT(childIdx < childReaders.size());
    return childReaders[childIdx].get();
}

void StructColumnReader::initializeRead(uint64_t rowGroupIdx,
    const std::vector<lbug_parquet::format::ColumnChunk>& columns,
    lbug_apache::thrift::protocol::TProtocol& protocol) {
    for (auto& child : childReaders) {
        child->initializeRead(rowGroupIdx, columns, protocol);
    }
}

uint64_t StructColumnReader::getTotalCompressedSize() {
    uint64_t size = 0;
    for (auto& child : childReaders) {
        size += child->getTotalCompressedSize();
    }
    return size;
}

void StructColumnReader::registerPrefetch(ThriftFileTransport& transport, bool allow_merge) {
    for (auto& child : childReaders) {
        child->registerPrefetch(transport, allow_merge);
    }
}

uint64_t StructColumnReader::read(uint64_t numValuesToRead, parquet_filter_t& filter,
    uint8_t* define_out, uint8_t* repeat_out, common::ValueVector* result) {
    auto& fieldVectors = common::StructVector::getFieldVectors(result);
    KU_ASSERT(common::StructType::getNumFields(type) == fieldVectors.size());
    if (pendingSkips > 0) {
        applyPendingSkips(pendingSkips);
    }

    uint64_t numValuesRead = numValuesToRead;
    for (auto i = 0u; i < fieldVectors.size(); i++) {
        auto numValuesChildrenRead = childReaders[i]->read(numValuesToRead, filter, define_out,
            repeat_out, fieldVectors[i].get());
        if (i == 0) {
            numValuesRead = numValuesChildrenRead;
        } else if (numValuesRead != numValuesChildrenRead) {
            throw std::runtime_error("Struct child row count mismatch");
        }
    }
    for (auto i = 0u; i < numValuesRead; i++) {
        result->setNull(i, define_out[i] < maxDefine);
    }

    return numValuesRead;
}

void StructColumnReader::skip(uint64_t num_values) {
    for (auto& child_reader : childReaders) {
        child_reader->skip(num_values);
    }
}

static bool TypeHasExactRowCount(const common::LogicalType& type) {
    switch (type.getLogicalTypeID()) {
    case common::LogicalTypeID::LIST:
    case common::LogicalTypeID::MAP:
        return false;
    case common::LogicalTypeID::STRUCT:
        for (auto kv : common::StructType::getFieldTypes(type)) {
            if (TypeHasExactRowCount(*kv)) {
                return true;
            }
        }
        return false;
    default:
        return true;
    }
}

uint64_t StructColumnReader::getGroupRowsAvailable() {
    for (auto i = 0u; i < childReaders.size(); i++) {
        if (TypeHasExactRowCount(childReaders[i]->getDataType())) {
            return childReaders[i]->getGroupRowsAvailable();
        }
    }
    return childReaders[0]->getGroupRowsAvailable();
}

} // namespace processor
} // namespace lbug
