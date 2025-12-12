#pragma once

#include "compressed_file_system.h"

namespace lbug {
namespace common {

class GZipFileSystem : public CompressedFileSystem {
public:
    static constexpr const idx_t BUFFER_SIZE = 1u << 15; // 32 KB
    static constexpr const uint8_t GZIP_COMPRESSION_DEFLATE = 0x08;
    static constexpr const uint8_t GZIP_FLAG_ASCII = 0x1;
    static constexpr const uint8_t GZIP_FLAG_MULTIPART = 0x2;
    static constexpr const uint8_t GZIP_FLAG_EXTRA = 0x4;
    static constexpr const uint8_t GZIP_FLAG_NAME = 0x8;
    static constexpr const uint8_t GZIP_FLAG_COMMENT = 0x10;
    static constexpr const uint8_t GZIP_FLAG_ENCRYPT = 0x20;
    static constexpr const uint8_t GZIP_HEADER_MINSIZE = 10;
    static constexpr const idx_t GZIP_HEADER_MAXSIZE = 1u << 15;
    static constexpr const uint8_t GZIP_FOOTER_SIZE = 8;
    static constexpr const unsigned char GZIP_FLAG_UNSUPPORTED =
        GZIP_FLAG_ASCII | GZIP_FLAG_MULTIPART | GZIP_FLAG_COMMENT | GZIP_FLAG_ENCRYPT;

public:
    std::unique_ptr<FileInfo> openCompressedFile(std::unique_ptr<FileInfo> fileInfo) override;

    std::unique_ptr<StreamWrapper> createStream() override;
    idx_t getInputBufSize() override { return BUFFER_SIZE; }
    idx_t getOutputBufSize() override { return BUFFER_SIZE; }
};

struct GZIPFileInfo : public CompressedFileInfo {
    GZIPFileInfo(CompressedFileSystem& compressedFS, std::unique_ptr<FileInfo> childFileInfo)
        : CompressedFileInfo{compressedFS, std::move(childFileInfo)} {
        initialize();
    }
};

} // namespace common
} // namespace lbug
