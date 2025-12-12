#include "common/file_system/gzip_file_system.h"

#include "common/exception/io.h"
#include "miniz.hpp"

namespace lbug {
namespace common {

std::unique_ptr<FileInfo> GZipFileSystem::openCompressedFile(std::unique_ptr<FileInfo> fileInfo) {
    return std::make_unique<GZIPFileInfo>(*this, std::move(fileInfo));
}

static idx_t consumeStr(FileInfo& input) {
    idx_t size = 1; // terminator
    char buffer[1];
    while (input.readFile(buffer, 1) == 1) {
        if (buffer[0] == '\0') {
            break;
        }
        size++;
    }
    return size;
}

struct MiniZStreamWrapper : public StreamWrapper {
    ~MiniZStreamWrapper() override { MiniZStreamWrapper::close(); }

    CompressedFileInfo* file = nullptr;
    std::unique_ptr<miniz::mz_stream> mzStreamPtr = nullptr;
    miniz::mz_ulong crc = 0;
    idx_t total_size = 0;

public:
    void initialize(CompressedFileInfo& fileInfo) override;

    bool read(StreamData& stream_data) override;

    void close() override;
};

static void verifyGZIPHeader(const uint8_t gzip_hdr[], idx_t read_count) {
    if (read_count != GZipFileSystem::GZIP_HEADER_MINSIZE) {
        throw IOException("Input is not a GZIP stream.");
    }
    if (gzip_hdr[0] != 0x1F || gzip_hdr[1] != 0x8B) {
        throw IOException("Input is not a GZIP stream.");
    }
    if (gzip_hdr[2] != GZipFileSystem::GZIP_COMPRESSION_DEFLATE) {
        throw IOException("Unsupported GZIP compression method.");
    }
    if (gzip_hdr[3] & GZipFileSystem::GZIP_FLAG_UNSUPPORTED) {
        throw IOException("Unsupported GZIP archive.");
    }
}

void MiniZStreamWrapper::initialize(CompressedFileInfo& fileInfo) {
    close();
    this->file = &fileInfo;
    mzStreamPtr = std::make_unique<miniz::mz_stream>();
    memset(mzStreamPtr.get(), 0, sizeof(miniz::mz_stream));
    uint8_t gzipHdr[GZipFileSystem::GZIP_HEADER_MINSIZE];

    idx_t dataStart = GZipFileSystem::GZIP_HEADER_MINSIZE;
    auto numBytesRead =
        fileInfo.childFileInfo->readFile(gzipHdr, GZipFileSystem::GZIP_HEADER_MINSIZE);
    verifyGZIPHeader(gzipHdr, numBytesRead);
    if (gzipHdr[3] & GZipFileSystem::GZIP_FLAG_EXTRA) {
        uint8_t gzipXLen[2];
        fileInfo.childFileInfo->seek(dataStart, SEEK_SET);
        fileInfo.childFileInfo->readFile(gzipXLen, 2);
        auto xlen = (uint8_t)gzipXLen[0] | (uint8_t)gzipXLen[1] << 8;
        dataStart += xlen + 2;
    }
    if (gzipHdr[3] & GZipFileSystem::GZIP_FLAG_NAME) {
        fileInfo.childFileInfo->seek(dataStart, SEEK_SET);
        dataStart += consumeStr(*fileInfo.childFileInfo);
    }
    fileInfo.childFileInfo->seek(dataStart, SEEK_SET);
    auto ret = miniz::mz_inflateInit2(mzStreamPtr.get(), -MZ_DEFAULT_WINDOW_BITS);
    // LCOV_EXCL_START
    if (ret != miniz::MZ_OK) {
        throw InternalException("Failed to initialize miniz");
    }
    // LCOV_EXCL_STOP
}

bool MiniZStreamWrapper::read(StreamData& sd) {
    if (sd.refresh) {
        uint32_t available = sd.inputBufEnd - sd.inputBufStart;
        if (available <= GZipFileSystem::GZIP_FOOTER_SIZE) {
            close();
            return true;
        }

        sd.refresh = false;
        auto bodyPtr = sd.inputBufStart + GZipFileSystem::GZIP_FOOTER_SIZE;
        uint8_t gzipHdr[GZipFileSystem::GZIP_HEADER_MINSIZE];
        memcpy(gzipHdr, bodyPtr, GZipFileSystem::GZIP_HEADER_MINSIZE);
        verifyGZIPHeader(gzipHdr, GZipFileSystem::GZIP_HEADER_MINSIZE);
        bodyPtr += GZipFileSystem::GZIP_HEADER_MINSIZE;
        if (gzipHdr[3] & GZipFileSystem::GZIP_FLAG_EXTRA) {
            auto xlen = (uint8_t)*bodyPtr | (uint8_t) * (bodyPtr + 1) << 8;
            bodyPtr += xlen + 2;
            KU_ASSERT((common::idx_t)(GZipFileSystem::GZIP_FOOTER_SIZE +
                                      GZipFileSystem::GZIP_HEADER_MINSIZE + 2 + xlen) <
                      GZipFileSystem::GZIP_HEADER_MAXSIZE);
        }
        if (gzipHdr[3] & GZipFileSystem::GZIP_FLAG_NAME) {
            char c = '\0';
            do {
                c = *bodyPtr;
                bodyPtr++;
            } while (c != '\0' && bodyPtr < sd.inputBufEnd);
            KU_ASSERT(bodyPtr - sd.inputBufStart < GZipFileSystem::GZIP_HEADER_MAXSIZE);
        }
        sd.inputBufStart = bodyPtr;
        if (sd.inputBufEnd - sd.inputBufStart < 1) {
            close();
            return true;
        }
        miniz::mz_inflateEnd(mzStreamPtr.get());
        auto sta = miniz::mz_inflateInit2(mzStreamPtr.get(), -MZ_DEFAULT_WINDOW_BITS);
        // LCOV_EXCL_START
        if (sta != miniz::MZ_OK) {
            throw InternalException("Failed to initialize miniz");
        }
        // LCOV_EXCL_STOP
    }

    mzStreamPtr->next_in = sd.inputBufStart;
    mzStreamPtr->avail_in = sd.inputBufEnd - sd.inputBufStart;
    mzStreamPtr->next_out = sd.outputBufEnd;
    mzStreamPtr->avail_out = sd.outputBuf.get() + sd.outputBufSize - sd.outputBufEnd;
    auto ret = miniz::mz_inflate(mzStreamPtr.get(), miniz::MZ_NO_FLUSH);
    // LCOV_EXCL_START
    if (ret != miniz::MZ_OK && ret != miniz::MZ_STREAM_END) {
        throw IOException(
            common::stringFormat("Failed to decode gzip stream: {}", miniz::mz_error(ret)));
    }
    // LCOV_EXCL_STOP
    sd.inputBufStart = (uint8_t*)mzStreamPtr->next_in;
    sd.inputBufEnd = sd.inputBufStart + mzStreamPtr->avail_in;
    sd.outputBufEnd = (uint8_t*)mzStreamPtr->next_out;
    KU_ASSERT(sd.outputBufEnd + mzStreamPtr->avail_out == sd.outputBuf.get() + sd.outputBufSize);

    if (ret == miniz::MZ_STREAM_END) {
        sd.refresh = true;
    }
    return false;
}

void MiniZStreamWrapper::close() {
    if (!mzStreamPtr) {
        return;
    }
    miniz::mz_inflateEnd(mzStreamPtr.get());
    mzStreamPtr = nullptr;
    file = nullptr;
}

std::unique_ptr<StreamWrapper> GZipFileSystem::createStream() {
    return std::make_unique<MiniZStreamWrapper>();
}

} // namespace common
} // namespace lbug
