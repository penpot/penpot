#include "processor/operator/persistent/reader/parquet/parquet_reader.h"

#include "binder/binder.h"
#include "common/exception/binder.h"
#include "common/exception/copy.h"
#include "common/file_system/virtual_file_system.h"
#include "common/string_format.h"
#include "function/table/bind_data.h"
#include "function/table/bind_input.h"
#include "function/table/table_function.h"
#include "processor/execution_context.h"
#include "processor/operator/persistent/reader/parquet/list_column_reader.h"
#include "processor/operator/persistent/reader/parquet/struct_column_reader.h"
#include "processor/operator/persistent/reader/parquet/thrift_tools.h"
#include "processor/operator/persistent/reader/reader_bind_utils.h"
#include "processor/warning_context.h"

using namespace lbug_parquet::format;

namespace lbug {
namespace processor {

using namespace lbug::function;
using namespace lbug::common;

ParquetReader::ParquetReader(std::string filePath, std::vector<bool> columnSkips,
    main::ClientContext* context)
    : filePath{std::move(filePath)}, columnSkips(std::move(columnSkips)), context{context} {
    initMetadata();
}

void ParquetReader::initializeScan(ParquetReaderScanState& state,
    std::vector<uint64_t> groups_to_read, VirtualFileSystem* vfs) {
    state.currentGroup = -1;
    state.finished = false;
    state.groupOffset = 0;
    state.groupIdxList = std::move(groups_to_read);
    if (!state.fileInfo || state.fileInfo->path != filePath) {
        state.prefetchMode = false;
        state.fileInfo =
            vfs->openFile(filePath, common::FileOpenFlags(FileFlags::READ_ONLY), context);
    }

    state.thriftFileProto = createThriftProtocol(state.fileInfo.get(), state.prefetchMode);
    state.rootReader = createReader();
    state.defineBuf.resize(DEFAULT_VECTOR_CAPACITY);
    state.repeatBuf.resize(DEFAULT_VECTOR_CAPACITY);
}

bool ParquetReader::scanInternal(ParquetReaderScanState& state, DataChunk& result) {
    if (state.finished) {
        return false;
    }

    // see if we have to switch to the next row group in the parquet file
    if (state.currentGroup < 0 || (int64_t)state.groupOffset >= getGroup(state).num_rows) {
        state.currentGroup++;
        state.groupOffset = 0;

        auto& trans = ku_dynamic_cast<ThriftFileTransport&>(*state.thriftFileProto->getTransport());
        trans.ClearPrefetch();
        state.currentGroupPrefetched = false;

        if ((uint64_t)state.currentGroup == state.groupIdxList.size()) {
            state.finished = true;
            return false;
        }

        uint64_t toScanCompressedBytes = 0;
        for (auto colIdx = 0u; colIdx < result.getNumValueVectors(); colIdx++) {
            prepareRowGroupBuffer(state, colIdx);

            auto fileColIdx = colIdx;

            auto rootReader = ku_dynamic_cast<StructColumnReader*>(state.rootReader.get());
            toScanCompressedBytes +=
                rootReader->getChildReader(fileColIdx)->getTotalCompressedSize();
        }

        auto& group = getGroup(state);
        if (state.prefetchMode && state.groupOffset != (uint64_t)group.num_rows) {

            uint64_t totalRowGroupSpan = getGroupSpan(state);

            double scanPercentage = (double)(toScanCompressedBytes) / totalRowGroupSpan;

            // LCOV_EXCL_START
            if (toScanCompressedBytes > totalRowGroupSpan) {
                throw CopyException("Malformed parquet file: sum of total compressed bytes "
                                    "of columns seems incorrect");
            }
            // LCOV_EXCL_STOP

            if (scanPercentage > ParquetReaderPrefetchConfig::WHOLE_GROUP_PREFETCH_MINIMUM_SCAN) {
                // Prefetch the whole row group
                if (!state.currentGroupPrefetched) {
                    auto totalCompressedSize = getGroupCompressedSize(state);
                    if (totalCompressedSize > 0) {
                        trans.Prefetch(getGroupOffset(state), totalRowGroupSpan);
                    }
                    state.currentGroupPrefetched = true;
                }
            } else {
                // Prefetch column-wise.
                for (auto colIdx = 0u; colIdx < result.getNumValueVectors(); colIdx++) {
                    auto fileColIdx = colIdx;
                    auto rootReader = ku_dynamic_cast<StructColumnReader*>(state.rootReader.get());

                    rootReader->getChildReader(fileColIdx)
                        ->registerPrefetch(trans, true /* lazy fetch */);
                }
                trans.FinalizeRegistration();
                trans.PrefetchRegistered();
            }
        }
        return true;
    }

    auto thisOutputChunkRows =
        std::min<uint64_t>(DEFAULT_VECTOR_CAPACITY, getGroup(state).num_rows - state.groupOffset);
    result.state->getSelVectorUnsafe().setSelSize(thisOutputChunkRows);

    if (thisOutputChunkRows == 0) {
        state.finished = true;
        return false; // end of last group, we are done
    }

    // we evaluate simple table filters directly in this scan, so we can skip decoding column data
    // that's never going to be relevant
    parquet_filter_t filterMask;
    filterMask.set();

    // mask out unused part of bitset
    for (auto i = thisOutputChunkRows; i < DEFAULT_VECTOR_CAPACITY; i++) {
        filterMask.set(i, false);
    }

    state.defineBuf.zero();
    state.repeatBuf.zero();

    auto definePtr = (uint8_t*)state.defineBuf.ptr;
    auto repeatPtr = (uint8_t*)state.repeatBuf.ptr;

    auto rootReader = ku_dynamic_cast<StructColumnReader*>(state.rootReader.get());
    for (auto colIdx = 0u; colIdx < result.getNumValueVectors(); colIdx++) {
        if (!columnSkips.empty() && columnSkips[colIdx]) {
            continue;
        }
        auto fileColIdx = colIdx;
        auto& resultVector = result.getValueVectorMutable(colIdx);
        auto childReader = rootReader->getChildReader(fileColIdx);
        auto rowsRead = childReader->read(resultVector.state->getSelVector().getSelSize(),
            filterMask, definePtr, repeatPtr, &resultVector);
        // LCOV_EXCL_START
        if (rowsRead != result.state->getSelVector().getSelSize()) {
            throw CopyException(
                stringFormat("Mismatch in parquet read for column {}, expected {} rows, got {}",
                    fileColIdx, result.state->getSelVector().getSelSize(), rowsRead));
        }
        // LCOV_EXCL_STOP
    }

    state.groupOffset += thisOutputChunkRows;
    return true;
}

void ParquetReader::scan(processor::ParquetReaderScanState& state, DataChunk& result) {
    while (scanInternal(state, result)) {
        if (result.state->getSelVector().getSelSize() > 0) {
            break;
        }
    }
}

void ParquetReader::initMetadata() {
    auto fileInfo = VirtualFileSystem::GetUnsafe(*context)->openFile(filePath,
        FileOpenFlags(FileFlags::READ_ONLY), context);
    auto proto = createThriftProtocol(fileInfo.get(), false);
    auto& transport = ku_dynamic_cast<ThriftFileTransport&>(*proto->getTransport());
    auto fileSize = transport.GetSize();
    // LCOV_EXCL_START
    if (fileSize < 12) {
        throw CopyException{
            stringFormat("File {} is too small to be a Parquet file", filePath.c_str())};
    }
    // LCOV_EXCL_STOP

    ResizeableBuffer buf;
    buf.resize(8);
    buf.zero();

    transport.SetLocation(fileSize - 8);
    transport.read((uint8_t*)buf.ptr, 8);

    // LCOV_EXCL_START
    if (memcmp(buf.ptr + 4, "PAR1", 4) != 0) {
        if (memcmp(buf.ptr + 4, "PARE", 4) == 0) {
            throw CopyException{stringFormat(
                "Encrypted Parquet files are not supported for file {}", fileInfo->path.c_str())};
        }
        throw CopyException{
            stringFormat("No magic bytes found at the end of file {}", fileInfo->path.c_str())};
    }
    // LCOV_EXCL_STOP
    // Read four-byte footer length from just before the end magic bytes.
    auto footerLen = *reinterpret_cast<uint32_t*>(buf.ptr);
    // LCOV_EXCL_START
    if (footerLen == 0 || fileSize < 12 + footerLen) {
        throw CopyException{stringFormat("Footer length error in file {}", fileInfo->path.c_str())};
    }
    // LCOV_EXCL_STOP
    auto metadataPos = fileSize - (footerLen + 8);
    transport.SetLocation(metadataPos);
    transport.Prefetch(metadataPos, footerLen);

    metadata = std::make_unique<FileMetaData>();
    metadata->read(proto.get());
}

std::unique_ptr<ColumnReader> ParquetReader::createReaderRecursive(uint64_t depth,
    uint64_t maxDefine, uint64_t maxRepeat, uint64_t& nextSchemaIdx, uint64_t& nextFileIdx) {
    KU_ASSERT(nextSchemaIdx < metadata->schema.size());
    auto& sEle = metadata->schema[nextSchemaIdx];
    auto thisIdx = nextSchemaIdx;

    auto repetition_type = FieldRepetitionType::REQUIRED;
    if (sEle.__isset.repetition_type && thisIdx > 0) {
        repetition_type = sEle.repetition_type;
    }
    if (repetition_type != FieldRepetitionType::REQUIRED) {
        maxDefine++;
    }
    if (repetition_type == FieldRepetitionType::REPEATED) {
        maxRepeat++;
    }
    if (sEle.__isset.num_children && sEle.num_children > 0) {
        std::vector<StructField> structFields;
        std::vector<std::unique_ptr<ColumnReader>> childrenReaders;
        uint64_t cIdx = 0;
        while (cIdx < (uint64_t)sEle.num_children) {
            nextSchemaIdx++;
            auto& childEle = metadata->schema[nextSchemaIdx];
            auto childReader =
                createReaderRecursive(depth + 1, maxDefine, maxRepeat, nextSchemaIdx, nextFileIdx);
            structFields.emplace_back(childEle.name, childReader->getDataType().copy());
            childrenReaders.push_back(std::move(childReader));
            cIdx++;
        }
        KU_ASSERT(!structFields.empty());
        std::unique_ptr<ColumnReader> result;
        LogicalType resultType;

        bool isRepeated = repetition_type == FieldRepetitionType::REPEATED;
        bool isList = sEle.__isset.converted_type && sEle.converted_type == ConvertedType::LIST;
        bool isMap = sEle.__isset.converted_type && sEle.converted_type == ConvertedType::MAP;
        bool isMapKV =
            sEle.__isset.converted_type && sEle.converted_type == ConvertedType::MAP_KEY_VALUE;
        if (!isMapKV && thisIdx > 0) {
            // check if the parent node of this is a map
            auto& parentEle = metadata->schema[thisIdx - 1];
            bool parentIsMap =
                parentEle.__isset.converted_type && parentEle.converted_type == ConvertedType::MAP;
            bool parentHasChildren = parentEle.__isset.num_children && parentEle.num_children == 1;
            isMapKV = parentIsMap && parentHasChildren;
        }

        if (isMapKV) {
            // LCOV_EXCL_START
            if (structFields.size() != 2) {
                throw CopyException{"MAP_KEY_VALUE requires two children"};
            }
            if (!isRepeated) {
                throw CopyException{"MAP_KEY_VALUE needs to be repeated"};
            }
            // LCOV_EXCL_STOP
            auto structType = LogicalType::STRUCT(std::move(structFields));
            resultType = LogicalType(LogicalTypeID::MAP,
                std::make_unique<ListTypeInfo>(std::move(structType)));

            auto structReader = std::make_unique<StructColumnReader>(*this,
                ListType::getChildType(resultType).copy(), sEle, thisIdx, maxDefine - 1,
                maxRepeat - 1, std::move(childrenReaders));
            return std::make_unique<ListColumnReader>(*this, std::move(resultType), sEle, thisIdx,
                maxDefine, maxRepeat, std::move(structReader),
                storage::MemoryManager::Get(*context));
        }

        if (structFields.size() > 1 || (!isList && !isMap && !isRepeated)) {
            resultType = LogicalType::STRUCT(std::move(structFields));
            result = std::make_unique<StructColumnReader>(*this, resultType.copy(), sEle, thisIdx,
                maxDefine, maxRepeat, std::move(childrenReaders));
        } else {
            // if we have a struct with only a single type, pull up
            resultType = structFields[0].getType().copy();
            result = std::move(childrenReaders[0]);
        }
        if (isRepeated) {
            resultType = LogicalType::LIST(resultType.copy());
            return std::make_unique<ListColumnReader>(*this, std::move(resultType), sEle, thisIdx,
                maxDefine, maxRepeat, std::move(result), storage::MemoryManager::Get(*context));
        }
        return result;
    } else {
        // LCOV_EXCL_START
        if (!sEle.__isset.type) {
            throw CopyException{"Node has neither num_children nor type set - this "
                                "violates the Parquet spec (corrupted file)"};
        }
        // LCOV_EXCL_STOP
        if (sEle.repetition_type == FieldRepetitionType::REPEATED) {
            auto derivedType = deriveLogicalType(sEle);
            auto listType = LogicalType::LIST(derivedType.copy());
            auto elementReader = ColumnReader::createReader(*this, std::move(derivedType), sEle,
                nextFileIdx++, maxDefine, maxRepeat);
            return std::make_unique<ListColumnReader>(*this, std::move(listType), sEle, thisIdx,
                maxDefine, maxRepeat, std::move(elementReader),
                storage::MemoryManager::Get(*context));
        }
        // TODO check return value of derive type or should we only do this on read()
        return ColumnReader::createReader(*this, deriveLogicalType(sEle), sEle, nextFileIdx++,
            maxDefine, maxRepeat);
    }
}

std::unique_ptr<ColumnReader> ParquetReader::createReader() {
    uint64_t nextSchemaIdx = 0;
    uint64_t nextFileIdx = 0;

    // LCOV_EXCL_START
    if (metadata->schema.empty()) {
        throw CopyException{"Parquet reader: no schema elements found"};
    }
    if (metadata->schema[0].num_children == 0) {
        throw CopyException{"Parquet reader: root schema element has no children"};
    }
    // LCOV_EXCL_STOP
    auto rootReader = createReaderRecursive(0, 0, 0, nextSchemaIdx, nextFileIdx);
    // LCOV_EXCL_START
    if (rootReader->getDataType().getPhysicalType() != PhysicalTypeID::STRUCT) {
        throw CopyException{"Root element of Parquet file must be a struct"};
    }
    // LCOV_EXCL_STOP
    for (auto& field : StructType::getFields(rootReader->getDataType())) {
        columnNames.push_back(field.getName());
        columnTypes.push_back(field.getType().copy());
    }

    KU_ASSERT(nextSchemaIdx == metadata->schema.size() - 1);
    KU_ASSERT(
        metadata->row_groups.empty() || nextFileIdx == metadata->row_groups[0].columns.size());
    return rootReader;
}

void ParquetReader::prepareRowGroupBuffer(ParquetReaderScanState& state, uint64_t /*colIdx*/) {
    auto& group = getGroup(state);
    state.rootReader->initializeRead(state.groupIdxList[state.currentGroup], group.columns,
        *state.thriftFileProto);
}

uint64_t ParquetReader::getGroupSpan(ParquetReaderScanState& state) {
    auto& group = getGroup(state);
    uint64_t min_offset = UINT64_MAX;
    uint64_t max_offset = 0;
    for (auto& column_chunk : group.columns) {
        // Set the min offset
        auto current_min_offset = UINT64_MAX;
        if (column_chunk.meta_data.__isset.dictionary_page_offset) {
            current_min_offset = std::min<uint64_t>(current_min_offset,
                column_chunk.meta_data.dictionary_page_offset);
        }
        if (column_chunk.meta_data.__isset.index_page_offset) {
            current_min_offset =
                std::min<uint64_t>(current_min_offset, column_chunk.meta_data.index_page_offset);
        }
        current_min_offset =
            std::min<uint64_t>(current_min_offset, column_chunk.meta_data.data_page_offset);
        min_offset = std::min<uint64_t>(current_min_offset, min_offset);
        max_offset = std::max<uint64_t>(max_offset,
            column_chunk.meta_data.total_compressed_size + current_min_offset);
    }

    return max_offset - min_offset;
}

LogicalType ParquetReader::deriveLogicalType(const lbug_parquet::format::SchemaElement& s_ele) {
    // inner node
    if (s_ele.type == Type::FIXED_LEN_BYTE_ARRAY && !s_ele.__isset.type_length) {
        // LCOV_EXCL_START
        throw CopyException("FIXED_LEN_BYTE_ARRAY requires length to be set");
        // LCOV_EXCL_STOP
    }
    if (s_ele.__isset.logicalType && s_ele.logicalType.__isset.UUID &&
        s_ele.type == Type::FIXED_LEN_BYTE_ARRAY) {
        return LogicalType::UUID();
    }
    if (s_ele.__isset.converted_type) {
        switch (s_ele.converted_type) {
        case ConvertedType::INT_8:
            if (s_ele.type == Type::INT32) {
                return LogicalType::INT8();
            } else {
                // LCOV_EXCL_START
                throw CopyException{"INT8 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::INT_16:
            if (s_ele.type == Type::INT32) {
                return LogicalType::INT16();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "INT16 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::INT_32:
            if (s_ele.type == Type::INT32) {
                return LogicalType::INT32();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "INT32 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::INT_64:
            if (s_ele.type == Type::INT64) {
                return LogicalType::INT64();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "INT64 converted type can only be set for value of Type::INT64"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::UINT_8:
            if (s_ele.type == Type::INT32) {
                return LogicalType::UINT8();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "UINT8 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::UINT_16:
            if (s_ele.type == Type::INT32) {
                return LogicalType::UINT16();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "UINT16 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::UINT_32:
            if (s_ele.type == Type::INT32) {
                return LogicalType::UINT32();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "UINT32 converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::UINT_64:
            if (s_ele.type == Type::INT64) {
                return LogicalType::UINT64();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "UINT64 converted type can only be set for value of Type::INT64"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::DATE:
            if (s_ele.type == Type::INT32) {
                return LogicalType::DATE();
            } else {
                // LCOV_EXCL_START
                throw CopyException{"DATE converted type can only be set for value of Type::INT32"};
                // LCOV_EXCL_STOP
            }
        case ConvertedType::TIMESTAMP_MICROS:
        case ConvertedType::TIMESTAMP_MILLIS:
            if (s_ele.type == Type::INT64) {
                return LogicalType::TIMESTAMP();
            } else {
                // LCOV_EXCL_START
                throw CopyException(
                    "TIMESTAMP converted type can only be set for value of Type::INT64");
                // LCOV_EXCL_STOP
            }
        case ConvertedType::INTERVAL: {
            return LogicalType::INTERVAL();
        }
        case ConvertedType::UTF8:
            switch (s_ele.type) {
            case Type::BYTE_ARRAY:
            case Type::FIXED_LEN_BYTE_ARRAY:
                return LogicalType::STRING();
                // LCOV_EXCL_START
            default:
                throw CopyException(
                    "UTF8 converted type can only be set for Type::(FIXED_LEN_)BYTE_ARRAY");
                // LCOV_EXCL_STOP
            }
        case ConvertedType::SERIAL:
            if (s_ele.type == Type::INT64) {
                return LogicalType::SERIAL();
            } else {
                // LCOV_EXCL_START
                throw CopyException{
                    "SERIAL converted type can only be set for value of Type::INT64"};
                // LCOV_EXCL_STOP
            }

        default:
            // LCOV_EXCL_START
            throw CopyException{"Unsupported converted type"};
            // LCOV_EXCL_STOP
        }
    } else {
        // no converted type set
        // use default type for each physical type
        switch (s_ele.type) {
        case Type::BOOLEAN:
            return LogicalType::BOOL();
        case Type::INT32:
            return LogicalType::INT32();
        case Type::INT64:
            return LogicalType::INT64();
        case Type::INT96:
            return LogicalType::TIMESTAMP();
        case Type::FLOAT:
            return LogicalType::FLOAT();
        case Type::DOUBLE:
            return LogicalType::DOUBLE();
        case Type::BYTE_ARRAY:
        case Type::FIXED_LEN_BYTE_ARRAY:
            // TODO(Ziyi): Support parquet copy option(binary_as_string).
            return LogicalType::BLOB();
        default:
            return LogicalType(LogicalTypeID::ANY);
        }
    }
}

uint64_t ParquetReader::getGroupCompressedSize(ParquetReaderScanState& state) {
    auto& group = getGroup(state);
    auto total_compressed_size = group.total_compressed_size;

    uint64_t calc_compressed_size = 0;

    // If the global total_compressed_size is not set, we can still calculate it
    if (group.total_compressed_size == 0) {
        for (auto& column_chunk : group.columns) {
            calc_compressed_size += column_chunk.meta_data.total_compressed_size;
        }
    }

    // LCOV_EXCL_START
    if (total_compressed_size != 0 && calc_compressed_size != 0 &&
        (uint64_t)total_compressed_size != calc_compressed_size) {
        throw CopyException(
            "mismatch between calculated compressed size and reported compressed size");
    }
    // LCOV_EXCL_STOP

    return total_compressed_size ? total_compressed_size : calc_compressed_size;
}

uint64_t ParquetReader::getGroupOffset(ParquetReaderScanState& state) {
    auto& group = getGroup(state);
    uint64_t minOffset = UINT64_MAX;

    for (auto& column_chunk : group.columns) {
        if (column_chunk.meta_data.__isset.dictionary_page_offset) {
            minOffset =
                std::min<uint64_t>(minOffset, column_chunk.meta_data.dictionary_page_offset);
        }
        if (column_chunk.meta_data.__isset.index_page_offset) {
            minOffset = std::min<uint64_t>(minOffset, column_chunk.meta_data.index_page_offset);
        }
        minOffset = std::min<uint64_t>(minOffset, column_chunk.meta_data.data_page_offset);
    }

    return minOffset;
}

ParquetScanSharedState::ParquetScanSharedState(FileScanInfo fileScanInfo, uint64_t numRows,
    main::ClientContext* context, std::vector<bool> columnSkips)
    : ScanFileWithProgressSharedState{std::move(fileScanInfo), numRows, context},
      columnSkips{columnSkips} {
    readers.push_back(std::make_unique<ParquetReader>(this->fileScanInfo.filePaths[fileIdx],
        columnSkips, context));
    totalRowsGroups = 0;
    for (auto i = fileIdx.load(); i < this->fileScanInfo.getNumFiles(); i++) {
        auto reader =
            std::make_unique<ParquetReader>(this->fileScanInfo.filePaths[i], columnSkips, context);
        totalRowsGroups += reader->getNumRowsGroups();
    }
    numBlocksReadByFiles = 0;
}

static bool parquetSharedStateNext(ParquetScanLocalState& localState,
    ParquetScanSharedState& sharedState) {
    std::lock_guard<std::mutex> mtx{sharedState.mtx};
    while (true) {
        if (sharedState.fileIdx >= sharedState.fileScanInfo.getNumFiles()) {
            return false;
        }
        if (sharedState.blockIdx < sharedState.readers[sharedState.fileIdx]->getNumRowsGroups()) {
            localState.reader = sharedState.readers[sharedState.fileIdx].get();
            localState.reader->initializeScan(*localState.state, {sharedState.blockIdx},
                VirtualFileSystem::GetUnsafe(*sharedState.context));
            sharedState.blockIdx++;
            return true;
        } else {
            sharedState.numBlocksReadByFiles +=
                sharedState.readers[sharedState.fileIdx]->getNumRowsGroups();
            sharedState.blockIdx = 0;
            sharedState.fileIdx++;
            if (sharedState.fileIdx >= sharedState.fileScanInfo.getNumFiles()) {
                return false;
            }
            sharedState.readers.push_back(std::make_unique<ParquetReader>(
                sharedState.fileScanInfo.filePaths[sharedState.fileIdx], sharedState.columnSkips,
                sharedState.context));
            continue;
        }
    }
}

static offset_t tableFunc(const TableFuncInput& input, TableFuncOutput& output) {
    auto& outputChunk = output.dataChunk;
    if (input.localState == nullptr) {
        return 0;
    }
    auto parquetScanLocalState = ku_dynamic_cast<ParquetScanLocalState*>(input.localState);
    auto parquetScanSharedState = ku_dynamic_cast<ParquetScanSharedState*>(input.sharedState);
    do {
        parquetScanLocalState->reader->scan(*parquetScanLocalState->state, outputChunk);
        if (outputChunk.state->getSelVector().getSelSize() > 0) {
            return outputChunk.state->getSelVector().getSelSize();
        }
        if (!parquetSharedStateNext(*parquetScanLocalState, *parquetScanSharedState)) {
            return outputChunk.state->getSelVector().getSelSize();
        }
    } while (true);
}

static void bindColumns(const ExtraScanTableFuncBindInput* bindInput, uint32_t fileIdx,
    std::vector<std::string>& columnNames, std::vector<LogicalType>& columnTypes,
    main::ClientContext* context) {
    auto reader = ParquetReader(bindInput->fileScanInfo.filePaths[fileIdx], {}, context);
    auto state = std::make_unique<processor::ParquetReaderScanState>();
    reader.initializeScan(*state, std::vector<uint64_t>{}, VirtualFileSystem::GetUnsafe(*context));
    for (auto i = 0u; i < reader.getNumColumns(); ++i) {
        columnNames.push_back(reader.getColumnName(i));
        columnTypes.push_back(reader.getColumnType(i).copy());
    }
}

static void bindColumns(const ExtraScanTableFuncBindInput* bindInput,
    std::vector<std::string>& columnNames, std::vector<LogicalType>& columnTypes,
    main::ClientContext* context) {
    KU_ASSERT(bindInput->fileScanInfo.getNumFiles() > 0);
    bindColumns(bindInput, 0 /* fileIdx */, columnNames, columnTypes, context);
    for (auto i = 1u; i < bindInput->fileScanInfo.getNumFiles(); ++i) {
        std::vector<std::string> tmpColumnNames;
        std::vector<LogicalType> tmpColumnTypes;
        bindColumns(bindInput, i, tmpColumnNames, tmpColumnTypes, context);
        ReaderBindUtils::validateNumColumns(columnTypes.size(), tmpColumnTypes.size());
        ReaderBindUtils::validateColumnTypes(columnNames, columnTypes, tmpColumnTypes);
    }
}

static row_idx_t getNumRows(std::vector<std::string> filePaths, uint64_t numColumns,
    main::ClientContext* context) {
    std::vector<bool> dummyColumnSkips(false, numColumns);
    row_idx_t numRows = 0;
    for (const auto& path : filePaths) {
        auto reader = std::make_unique<ParquetReader>(path, dummyColumnSkips, context);
        numRows += reader->getMetadata()->num_rows;
    }
    return numRows;
}

static std::unique_ptr<TableFuncBindData> bindFunc(main::ClientContext* context,
    const TableFuncBindInput* input) {
    auto scanInput = ku_dynamic_cast<ExtraScanTableFuncBindInput*>(input->extraInput.get());
    const auto& options = scanInput->fileScanInfo.options;
    if (options.size() > 1 ||
        (options.size() == 1 && !options.contains(CopyConstants::IGNORE_ERRORS_OPTION_NAME))) {
        throw BinderException{"Copy from Parquet cannot have options other than IGNORE_ERRORS."};
    }
    std::vector<std::string> detectedColumnNames;
    std::vector<LogicalType> detectedColumnTypes;
    bindColumns(scanInput, detectedColumnNames, detectedColumnTypes, context);
    if (!scanInput->expectedColumnNames.empty()) {
        ReaderBindUtils::validateNumColumns(scanInput->expectedColumnNames.size(),
            detectedColumnNames.size());
        detectedColumnNames = scanInput->expectedColumnNames;
    }

    detectedColumnNames =
        TableFunction::extractYieldVariables(detectedColumnNames, input->yieldVariables);
    auto resultColumns = input->binder->createVariables(detectedColumnNames, detectedColumnTypes);
    auto bindData = std::make_unique<ScanFileBindData>(std::move(resultColumns),
        getNumRows(scanInput->fileScanInfo.filePaths, detectedColumnNames.size(), context),
        scanInput->fileScanInfo.copy(), context);
    return bindData;
}

static std::unique_ptr<TableFuncSharedState> initSharedState(
    const TableFuncInitSharedStateInput& input) {
    auto bindData = input.bindData->constPtrCast<ScanFileBindData>();
    return std::make_unique<ParquetScanSharedState>(bindData->fileScanInfo.copy(),
        bindData->numRows, bindData->context, bindData->getColumnSkips());
}

static std::unique_ptr<TableFuncLocalState> initLocalState(
    const TableFuncInitLocalStateInput& input) {
    auto sharedState = input.sharedState.ptrCast<ParquetScanSharedState>();
    auto localState = std::make_unique<ParquetScanLocalState>();
    if (!parquetSharedStateNext(*localState, *sharedState)) {
        return nullptr;
    }
    return localState;
}

static double progressFunc(TableFuncSharedState* sharedState) {
    auto state = sharedState->ptrCast<ParquetScanSharedState>();
    if (state->fileIdx >= state->fileScanInfo.getNumFiles()) {
        return 1.0;
    }
    if (state->totalRowsGroups == 0) {
        return 0.0;
    }
    uint64_t totalReadSize = state->numBlocksReadByFiles + state->blockIdx;
    return static_cast<double>(totalReadSize) / state->totalRowsGroups;
}

static void finalizeFunc(const ExecutionContext* ctx, TableFuncSharedState*) {
    WarningContext::Get(*ctx->clientContext)->defaultPopulateAllWarnings(ctx->queryID);
}

function_set ParquetScanFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<TableFunction>(name, std::vector{LogicalTypeID::STRING});
    function->tableFunc = tableFunc;
    function->bindFunc = bindFunc;
    function->initSharedStateFunc = initSharedState;
    function->initLocalStateFunc = initLocalState;
    function->progressFunc = progressFunc;
    function->finalizeFunc = finalizeFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace processor
} // namespace lbug
