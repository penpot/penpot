#pragma once

#include "column_reader.h"
#include "common/data_chunk/data_chunk.h"
#include "common/file_system/virtual_file_system.h"
#include "common/types/types.h"
#include "function/function.h"
#include "function/table/scan_file_function.h"
#include "parquet_types.h"
#include "protocol/TCompactProtocol.h"
#include "resizable_buffer.h"

namespace lbug {
namespace processor {

struct ParquetReaderPrefetchConfig {
    // Percentage of data in a row group span that should be scanned for enabling whole group
    // prefetch
    static constexpr double WHOLE_GROUP_PREFETCH_MINIMUM_SCAN = 0.95;
};

struct ParquetReaderScanState {
    std::vector<uint64_t> groupIdxList;
    int64_t currentGroup = -1;
    uint64_t groupOffset = UINT64_MAX;
    std::unique_ptr<common::FileInfo> fileInfo;
    std::unique_ptr<ColumnReader> rootReader;
    std::unique_ptr<lbug_apache::thrift::protocol::TProtocol> thriftFileProto;

    bool finished = false;

    ResizeableBuffer defineBuf;
    ResizeableBuffer repeatBuf;

    // TODO(Ziyi): We currently only support reading from local file system, thus the prefetch
    // mode is disabled by default. Add this back when we support remote file system.
    bool prefetchMode = false;
    bool currentGroupPrefetched = false;
};

class ParquetReader {
public:
    ParquetReader(std::string filePath, std::vector<bool> columnSkips,
        main::ClientContext* context);
    ~ParquetReader() = default;

    void initializeScan(ParquetReaderScanState& state, std::vector<uint64_t> groups_to_read,
        common::VirtualFileSystem* vfs);
    bool scanInternal(ParquetReaderScanState& state, common::DataChunk& result);
    void scan(ParquetReaderScanState& state, common::DataChunk& result);
    uint64_t getNumRowsGroups() { return metadata->row_groups.size(); }

    uint32_t getNumColumns() const { return columnNames.size(); }
    std::string getColumnName(uint32_t idx) const { return columnNames[idx]; }
    const common::LogicalType& getColumnType(uint32_t idx) const { return columnTypes[idx]; }

    lbug_parquet::format::FileMetaData* getMetadata() const { return metadata.get(); }

private:
    std::unique_ptr<lbug_apache::thrift::protocol::TProtocol> createThriftProtocol(
        common::FileInfo* fileInfo_, bool prefetch_mode) {
        return std::make_unique<
            lbug_apache::thrift::protocol::TCompactProtocolT<ThriftFileTransport>>(
            std::make_shared<ThriftFileTransport>(fileInfo_, prefetch_mode));
    }
    const lbug_parquet::format::RowGroup& getGroup(ParquetReaderScanState& state) {
        KU_ASSERT(
            state.currentGroup >= 0 && (uint64_t)state.currentGroup < state.groupIdxList.size());
        KU_ASSERT(state.groupIdxList[state.currentGroup] < metadata->row_groups.size());
        return metadata->row_groups[state.groupIdxList[state.currentGroup]];
    }
    static common::LogicalType deriveLogicalType(const lbug_parquet::format::SchemaElement& s_ele);
    void initMetadata();
    std::unique_ptr<ColumnReader> createReader();
    std::unique_ptr<ColumnReader> createReaderRecursive(uint64_t depth, uint64_t maxDefine,
        uint64_t maxRepeat, uint64_t& nextSchemaIdx, uint64_t& nextFileIdx);
    void prepareRowGroupBuffer(ParquetReaderScanState& state, uint64_t colIdx);
    // Group span is the distance between the min page offset and the max page offset plus the max
    // page compressed size
    uint64_t getGroupSpan(ParquetReaderScanState& state);
    uint64_t getGroupCompressedSize(ParquetReaderScanState& state);
    uint64_t getGroupOffset(ParquetReaderScanState& state);

private:
    std::string filePath;
    std::vector<bool> columnSkips;
    std::vector<std::string> columnNames;
    std::vector<common::LogicalType> columnTypes;

    std::unique_ptr<lbug_parquet::format::FileMetaData> metadata;
    main::ClientContext* context;
};

struct ParquetScanSharedState final : function::ScanFileWithProgressSharedState {
    explicit ParquetScanSharedState(common::FileScanInfo fileScanInfo, uint64_t numRows,
        main::ClientContext* context, std::vector<bool> columnSkips);

    std::vector<std::unique_ptr<ParquetReader>> readers;
    std::vector<bool> columnSkips;
    uint64_t totalRowsGroups;
    std::atomic<uint64_t> numBlocksReadByFiles;
};

struct ParquetScanLocalState final : function::TableFuncLocalState {
    ParquetScanLocalState() : reader(nullptr) {
        state = std::make_unique<ParquetReaderScanState>();
    }

    ParquetReader* reader;
    std::unique_ptr<ParquetReaderScanState> state;
};

struct ParquetScanFunction {
    static constexpr const char* name = "READ_PARQUET";

    static function::function_set getFunctionSet();
};

} // namespace processor
} // namespace lbug
