#pragma once

#include <string>
#include <vector>

#include "common/data_chunk/data_chunk.h"
#include "common/types/types.h"
#include "function/function.h"
#include "function/table/scan_file_function.h"

namespace lbug {
namespace processor {

class NpyReader {
public:
    explicit NpyReader(const std::string& filePath);

    ~NpyReader();

    size_t getNumElementsPerRow() const;

    uint8_t* getPointerToRow(size_t row) const;

    inline size_t getNumRows() const { return shape[0]; }

    void readBlock(common::block_idx_t blockIdx, common::ValueVector* vectorToRead) const;

    // Used in tests only.
    inline common::LogicalTypeID getType() const { return type; }
    inline std::vector<size_t> getShape() const { return shape; }

    void validate(const common::LogicalType& type_, common::offset_t numRows);

private:
    void parseHeader();
    void parseType(std::string descr);

private:
    std::string filePath;
    int fd;
    size_t fileSize;
    void* mmapRegion;
    size_t dataOffset;
    std::vector<size_t> shape;
    common::LogicalTypeID type;
};

class NpyMultiFileReader {
public:
    explicit NpyMultiFileReader(const std::vector<std::string>& filePaths);

    void readBlock(common::block_idx_t blockIdx, common::DataChunk& dataChunkToRead) const;

private:
    std::vector<std::unique_ptr<NpyReader>> fileReaders;
};

struct NpyScanSharedState final : public function::ScanFileSharedState {
    explicit NpyScanSharedState(const common::FileScanInfo fileScanInfo, uint64_t numRows);

    std::unique_ptr<NpyMultiFileReader> npyMultiFileReader;
};

struct NpyScanFunction {
    static constexpr const char* name = "READ_NPY";

    static function::function_set getFunctionSet();
};

} // namespace processor
} // namespace lbug
