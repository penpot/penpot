#pragma once

#include "common/file_system/file_system.h"
#include "common/types/types.h"

namespace lbug {
namespace common {

struct StreamData {
    bool refresh = false;
    std::unique_ptr<uint8_t[]> inputBuf;
    std::unique_ptr<uint8_t[]> outputBuf;
    uint8_t* inputBufStart = nullptr;
    uint8_t* inputBufEnd = nullptr;
    uint8_t* outputBufStart = nullptr;
    uint8_t* outputBufEnd = nullptr;
    common::idx_t inputBufSize = 0;
    common::idx_t outputBufSize = 0;
};

struct CompressedFileInfo;

struct StreamWrapper {
    virtual ~StreamWrapper() = default;
    virtual void initialize(CompressedFileInfo& file) = 0;
    virtual bool read(StreamData& stream_data) = 0;
    virtual void close() = 0;
};

class CompressedFileSystem : public FileSystem {
public:
    virtual std::unique_ptr<FileInfo> openCompressedFile(std::unique_ptr<FileInfo> fileInfo) = 0;
    virtual std::unique_ptr<StreamWrapper> createStream() = 0;
    virtual idx_t getInputBufSize() = 0;
    virtual idx_t getOutputBufSize() = 0;

    bool canPerformSeek() const override { return false; }

protected:
    std::vector<std::string> glob(main::ClientContext* /*context*/,
        const std::string& /*path*/) const override {
        KU_UNREACHABLE;
    }

    void readFromFile(FileInfo& /*fileInfo*/, void* /*buffer*/, uint64_t /*numBytes*/,
        uint64_t /*position*/) const override;

    int64_t readFile(FileInfo& fileInfo, void* buf, size_t numBytes) const override;

    void writeFile(FileInfo& /*fileInfo*/, const uint8_t* /*buffer*/, uint64_t /*numBytes*/,
        uint64_t /*offset*/) const override {
        KU_UNREACHABLE;
    }

    void reset(FileInfo& fileInfo) override;

    int64_t seek(FileInfo& /*fileInfo*/, uint64_t /*offset*/, int /*whence*/) const override {
        KU_UNREACHABLE;
    }

    uint64_t getFileSize(const FileInfo& fileInfo) const override;

    void syncFile(const FileInfo& fileInfo) const override;
};

struct CompressedFileInfo : public FileInfo {
    CompressedFileSystem& compressedFS;
    std::unique_ptr<FileInfo> childFileInfo;
    StreamData streamData;
    idx_t currentPos = 0;
    std::unique_ptr<StreamWrapper> stream_wrapper;

    CompressedFileInfo(CompressedFileSystem& compressedFS, std::unique_ptr<FileInfo> childFileInfo)
        : FileInfo{childFileInfo->path, &compressedFS}, compressedFS{compressedFS},
          childFileInfo{std::move(childFileInfo)} {}
    ~CompressedFileInfo() override { close(); }

    void initialize();
    int64_t readData(void* buffer, size_t numBytes);
    void close();
};

} // namespace common
} // namespace lbug
