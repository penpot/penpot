#include "processor/operator/persistent/writer/parquet/parquet_writer.h"

#include "common/constants.h"
#include "common/data_chunk/data_chunk.h"
#include "common/exception/runtime.h"
#include "common/file_system/virtual_file_system.h"
#include "common/system_config.h"
#include "main/client_context.h"
#include "protocol/TCompactProtocol.h"
#include "storage/buffer_manager/memory_manager.h"

namespace lbug {
namespace processor {

using namespace lbug_parquet::format;
using namespace lbug::common;

ParquetWriter::ParquetWriter(std::string fileName, std::vector<common::LogicalType> types,
    std::vector<std::string> columnNames, lbug_parquet::format::CompressionCodec::type codec,
    main::ClientContext* context)
    : fileName{std::move(fileName)}, types{std::move(types)}, columnNames{std::move(columnNames)},
      codec{codec}, fileOffset{0}, mm{storage::MemoryManager::Get(*context)} {
    fileInfo = VirtualFileSystem::GetUnsafe(*context)->openFile(this->fileName,
        FileOpenFlags(FileFlags::WRITE | FileFlags::CREATE_AND_TRUNCATE_IF_EXISTS), context);
    // Parquet files start with the string "PAR1".
    fileInfo->writeFile(reinterpret_cast<const uint8_t*>(ParquetConstants::PARQUET_MAGIC_WORDS),
        strlen(ParquetConstants::PARQUET_MAGIC_WORDS), fileOffset);
    fileOffset += strlen(ParquetConstants::PARQUET_MAGIC_WORDS);
    lbug_apache::thrift::protocol::TCompactProtocolFactoryT<ParquetWriterTransport> tprotoFactory;
    protocol = tprotoFactory.getProtocol(
        std::make_shared<ParquetWriterTransport>(fileInfo.get(), fileOffset));

    fileMetaData.num_rows = 0;
    fileMetaData.version = 1;

    fileMetaData.__isset.created_by = true;
    fileMetaData.created_by = "LBUG";

    fileMetaData.schema.resize(1);

    // populate root schema object
    fileMetaData.schema[0].name = "lbug_schema";
    fileMetaData.schema[0].num_children = this->types.size();
    fileMetaData.schema[0].__isset.num_children = true;
    fileMetaData.schema[0].repetition_type = lbug_parquet::format::FieldRepetitionType::REQUIRED;
    fileMetaData.schema[0].__isset.repetition_type = true;

    std::vector<std::string> schemaPath;
    for (auto i = 0u; i < this->types.size(); i++) {
        columnWriters.push_back(ColumnWriter::createWriterRecursive(fileMetaData.schema, *this,
            this->types[i], this->columnNames[i], schemaPath, mm));
    }
}

Type::type ParquetWriter::convertToParquetType(const LogicalType& type) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
        return Type::BOOLEAN;
    case LogicalTypeID::INT8:
    case LogicalTypeID::INT16:
    case LogicalTypeID::INT32:
    case LogicalTypeID::UINT8:
    case LogicalTypeID::UINT16:
    case LogicalTypeID::UINT32:
    case LogicalTypeID::DATE:
        return Type::INT32;
    case LogicalTypeID::UINT64:
    case LogicalTypeID::INT64:
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP_NS:
    case LogicalTypeID::TIMESTAMP_MS:
    case LogicalTypeID::TIMESTAMP_SEC:
    case LogicalTypeID::TIMESTAMP:
        return Type::INT64;
    case LogicalTypeID::FLOAT:
        return Type::FLOAT;
    case LogicalTypeID::INT128:
    case LogicalTypeID::DOUBLE:
        return Type::DOUBLE;
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING:
        return Type::BYTE_ARRAY;
    case LogicalTypeID::UUID:
    case LogicalTypeID::INTERVAL:
        return Type::FIXED_LEN_BYTE_ARRAY;
    default:
        throw RuntimeException{
            stringFormat("Writing a column with type: {} to parquet is not supported.",
                LogicalTypeUtils::toString(type.getLogicalTypeID()))};
    }
}

void ParquetWriter::setSchemaProperties(const LogicalType& type, SchemaElement& schemaElement) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::INT8: {
        schemaElement.converted_type = ConvertedType::INT_8;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::INT16: {
        schemaElement.converted_type = ConvertedType::INT_16;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::INT32: {
        schemaElement.converted_type = ConvertedType::INT_32;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::INT64: {
        schemaElement.converted_type = ConvertedType::INT_64;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::UINT8: {
        schemaElement.converted_type = ConvertedType::UINT_8;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::UINT16: {
        schemaElement.converted_type = ConvertedType::UINT_16;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::UINT32: {
        schemaElement.converted_type = ConvertedType::UINT_32;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::UINT64: {
        schemaElement.converted_type = ConvertedType::UINT_64;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::DATE: {
        schemaElement.converted_type = ConvertedType::DATE;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::STRING: {
        schemaElement.converted_type = ConvertedType::UTF8;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::INTERVAL: {
        schemaElement.type_length = common::ParquetConstants::PARQUET_INTERVAL_SIZE;
        schemaElement.converted_type = ConvertedType::INTERVAL;
        schemaElement.__isset.type_length = true;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP_NS:
    case LogicalTypeID::TIMESTAMP_SEC:
    case LogicalTypeID::TIMESTAMP: {
        schemaElement.converted_type = ConvertedType::TIMESTAMP_MICROS;
        schemaElement.__isset.converted_type = true;
        schemaElement.__isset.logicalType = true;
        schemaElement.logicalType.__isset.TIMESTAMP = true;
        schemaElement.logicalType.TIMESTAMP.isAdjustedToUTC =
            (type.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_TZ);
        schemaElement.logicalType.TIMESTAMP.unit.__isset.MICROS = true;
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        schemaElement.converted_type = ConvertedType::TIMESTAMP_MILLIS;
        schemaElement.__isset.converted_type = true;
        schemaElement.__isset.logicalType = true;
        schemaElement.logicalType.__isset.TIMESTAMP = true;
        schemaElement.logicalType.TIMESTAMP.isAdjustedToUTC = false;
        schemaElement.logicalType.TIMESTAMP.unit.__isset.MILLIS = true;
    } break;
    case LogicalTypeID::SERIAL: {
        schemaElement.converted_type = ConvertedType::SERIAL;
        schemaElement.__isset.converted_type = true;
    } break;
    case LogicalTypeID::UUID: {
        schemaElement.type_length = common::ParquetConstants::PARQUET_UUID_SIZE;
        schemaElement.__isset.type_length = true;
        schemaElement.__isset.logicalType = true;
        schemaElement.logicalType.__isset.UUID = true;
    } break;
    default:
        break;
    }
}

void ParquetWriter::flush(FactorizedTable& ft) {
    if (ft.getNumTuples() == 0) {
        return;
    }

    PreparedRowGroup preparedRowGroup;
    prepareRowGroup(ft, preparedRowGroup);
    flushRowGroup(preparedRowGroup);
    ft.clear();
}

void ParquetWriter::prepareRowGroup(FactorizedTable& ft, PreparedRowGroup& result) {
    // set up a new row group for this chunk collection
    auto& row_group = result.rowGroup;
    row_group.num_rows = ft.getTotalNumFlatTuples();
    row_group.total_byte_size = row_group.num_rows * ft.getTableSchema()->getNumBytesPerTuple();
    row_group.__isset.file_offset = true;

    auto& states = result.states;
    // iterate over each of the columns of the chunk collection and write them
    KU_ASSERT(ft.getTableSchema()->getNumColumns() == columnWriters.size());
    std::vector<std::unique_ptr<ColumnWriterState>> writerStates;
    std::unique_ptr<DataChunk> unflatDataChunkToRead =
        std::make_unique<DataChunk>(ft.getTableSchema()->getNumUnFlatColumns());
    std::unique_ptr<DataChunk> flatDataChunkToRead = std::make_unique<DataChunk>(
        ft.getTableSchema()->getNumFlatColumns(), DataChunkState::getSingleValueDataChunkState());
    std::vector<ValueVector*> vectorsToRead;
    vectorsToRead.reserve(columnWriters.size());
    auto numFlatVectors = 0;
    for (auto i = 0u; i < columnWriters.size(); i++) {
        writerStates.emplace_back(columnWriters[i]->initializeWriteState(row_group));
        auto vector = std::make_unique<ValueVector>(types[i].copy(), mm);
        vectorsToRead.push_back(vector.get());
        if (ft.getTableSchema()->getColumn(i)->isFlat()) {
            flatDataChunkToRead->insert(numFlatVectors, std::move(vector));
            numFlatVectors++;
        } else {
            unflatDataChunkToRead->insert(i - numFlatVectors, std::move(vector));
        }
    }
    uint64_t numTuplesRead = 0u;
    while (numTuplesRead < ft.getNumTuples()) {
        readFromFT(ft, vectorsToRead, numTuplesRead);
        for (auto i = 0u; i < columnWriters.size(); i++) {
            if (columnWriters[i]->hasAnalyze()) {
                columnWriters[i]->analyze(*writerStates[i], nullptr, vectorsToRead[i],
                    getNumTuples(unflatDataChunkToRead.get()));
            }
        }
    }

    for (auto i = 0u; i < columnWriters.size(); i++) {
        if (columnWriters[i]->hasAnalyze()) {
            columnWriters[i]->finalizeAnalyze(*writerStates[i]);
        }
    }

    numTuplesRead = 0u;
    while (numTuplesRead < ft.getNumTuples()) {
        readFromFT(ft, vectorsToRead, numTuplesRead);
        for (auto i = 0u; i < columnWriters.size(); i++) {
            columnWriters[i]->prepare(*writerStates[i], nullptr, vectorsToRead[i],
                getNumTuples(unflatDataChunkToRead.get()));
        }
    }

    for (auto i = 0u; i < columnWriters.size(); i++) {
        columnWriters[i]->beginWrite(*writerStates[i]);
    }

    numTuplesRead = 0u;
    while (numTuplesRead < ft.getNumTuples()) {
        readFromFT(ft, vectorsToRead, numTuplesRead);
        for (auto i = 0u; i < columnWriters.size(); i++) {
            columnWriters[i]->write(*writerStates[i], vectorsToRead[i],
                getNumTuples(unflatDataChunkToRead.get()));
        }
    }

    for (auto& write_state : writerStates) {
        states.push_back(std::move(write_state));
    }
}

void ParquetWriter::flushRowGroup(PreparedRowGroup& rowGroup) {
    std::lock_guard<std::mutex> glock(lock);
    auto& parquetRowGroup = rowGroup.rowGroup;
    auto& states = rowGroup.states;
    if (states.empty()) {
        throw RuntimeException("Attempting to flush a row group with no rows");
    }
    parquetRowGroup.file_offset = fileOffset;
    for (auto i = 0u; i < states.size(); i++) {
        auto write_state = std::move(states[i]);
        columnWriters[i]->finalizeWrite(*write_state);
    }

    // Append the row group to the file meta data.
    fileMetaData.row_groups.push_back(parquetRowGroup);
    fileMetaData.num_rows += parquetRowGroup.num_rows;
}

void ParquetWriter::readFromFT(FactorizedTable& ft, std::vector<ValueVector*> vectorsToRead,
    uint64_t& numTuplesRead) {
    auto numTuplesToRead =
        ft.getTableSchema()->getNumUnFlatColumns() != 0 ?
            1 :
            std::min<uint64_t>(ft.getNumTuples() - numTuplesRead, DEFAULT_VECTOR_CAPACITY);
    ft.scan(vectorsToRead, numTuplesRead, numTuplesToRead);
    numTuplesRead += numTuplesToRead;
}

void ParquetWriter::finalize() {
    auto startOffset = fileOffset;
    fileMetaData.write(protocol.get());
    uint32_t metadataSize = fileOffset - startOffset;
    fileInfo->writeFile(reinterpret_cast<const uint8_t*>(&metadataSize), sizeof(metadataSize),
        fileOffset);
    fileOffset += sizeof(uint32_t);

    // Parquet files also end with the string "PAR1".
    fileInfo->writeFile(reinterpret_cast<const uint8_t*>(ParquetConstants::PARQUET_MAGIC_WORDS),
        strlen(ParquetConstants::PARQUET_MAGIC_WORDS), fileOffset);
    fileOffset += strlen(ParquetConstants::PARQUET_MAGIC_WORDS);
}

} // namespace processor
} // namespace lbug
