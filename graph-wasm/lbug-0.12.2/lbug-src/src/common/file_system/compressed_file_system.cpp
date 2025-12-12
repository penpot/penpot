#include "common/file_system/compressed_file_system.h"

#include <string.h>

#include "common/exception/io.h"

namespace lbug {
namespace common {

int64_t CompressedFileSystem::readFile(lbug::common::FileInfo& fileInfo, void* buf,
    size_t numBytes) const {
    auto& compressedFileInfo = fileInfo.cast<CompressedFileInfo>();
    return compressedFileInfo.readData(buf, numBytes);
}

void CompressedFileSystem::reset(lbug::common::FileInfo& fileInfo) {
    auto& compressedFileInfo = fileInfo.cast<CompressedFileInfo>();
    compressedFileInfo.childFileInfo->reset();
    compressedFileInfo.initialize();
}

uint64_t CompressedFileSystem::getFileSize(const lbug::common::FileInfo& fileInfo) const {
    auto& compressedFileInfo = fileInfo.constCast<CompressedFileInfo>();
    return compressedFileInfo.childFileInfo->getFileSize();
}

void CompressedFileSystem::syncFile(const lbug::common::FileInfo& fileInfo) const {
    auto& compressedFileInfo = fileInfo.constCast<CompressedFileInfo>();
    return compressedFileInfo.childFileInfo->syncFile();
}

void CompressedFileSystem::readFromFile(FileInfo& /*fileInfo*/, void* /*buffer*/,
    uint64_t /*numBytes*/, uint64_t /*position*/) const {
    throw IOException("Only sequential read is allowed in compressed file system.");
}

void CompressedFileInfo::initialize() {
    close();
    streamData.inputBufSize = compressedFS.getInputBufSize();
    streamData.outputBufSize = compressedFS.getOutputBufSize();
    streamData.inputBuf = std::make_unique<uint8_t[]>(streamData.inputBufSize);
    streamData.inputBufStart = streamData.inputBuf.get();
    streamData.inputBufEnd = streamData.inputBuf.get();
    streamData.outputBuf = std::make_unique<uint8_t[]>(streamData.outputBufSize);
    streamData.outputBufStart = streamData.outputBuf.get();
    streamData.outputBufEnd = streamData.outputBuf.get();
    currentPos = 0;
    stream_wrapper = compressedFS.createStream();
    stream_wrapper->initialize(*this);
}

int64_t CompressedFileInfo::readData(void* buffer, size_t numBytes) {
    common::idx_t totalNumBytesRead = 0;
    while (true) {
        if (streamData.outputBufStart != streamData.outputBufEnd) {
            auto available =
                std::min<idx_t>(numBytes, streamData.outputBufEnd - streamData.outputBufStart);
            memcpy(reinterpret_cast<uint8_t*>(buffer) + totalNumBytesRead,
                streamData.outputBufStart, available);
            streamData.outputBufStart += available;
            totalNumBytesRead += available;
            numBytes -= available;
            if (numBytes == 0) {
                return totalNumBytesRead;
            }
        }
        if (!stream_wrapper) {
            return totalNumBytesRead;
        }
        currentPos += streamData.inputBufEnd - streamData.inputBufStart;
        streamData.outputBufStart = streamData.outputBuf.get();
        streamData.outputBufEnd = streamData.outputBuf.get();
        if (streamData.refresh &&
            (streamData.inputBufEnd == streamData.inputBuf.get() + streamData.inputBufSize)) {
            auto numBytesLeftInBuf = streamData.inputBufEnd - streamData.inputBufStart;
            memmove(streamData.inputBuf.get(), streamData.inputBufStart, numBytesLeftInBuf);
            streamData.inputBufStart = streamData.inputBuf.get();
            auto sz = childFileInfo->readFile(streamData.inputBufStart + numBytesLeftInBuf,
                streamData.inputBufSize - numBytesLeftInBuf);
            streamData.inputBufEnd = streamData.inputBufStart + numBytesLeftInBuf + sz;
            if (sz <= 0) {
                stream_wrapper.reset();
                break;
            }
        }

        if (streamData.inputBufStart == streamData.inputBufEnd) {
            streamData.inputBufStart = streamData.inputBuf.get();
            streamData.inputBufEnd = streamData.inputBufStart;
            auto sz = childFileInfo->readFile(streamData.inputBuf.get(), streamData.inputBufSize);
            if (sz <= 0) {
                stream_wrapper.reset();
                break;
            }
            streamData.inputBufEnd = streamData.inputBufStart + sz;
        }

        auto finished = stream_wrapper->read(streamData);
        if (finished) {
            stream_wrapper.reset();
        }
    }
    return totalNumBytesRead;
}

void CompressedFileInfo::close() {
    if (stream_wrapper) {
        stream_wrapper->close();
        stream_wrapper.reset();
    }
    streamData.inputBuf.reset();
    streamData.outputBuf.reset();
    streamData.outputBufStart = nullptr;
    streamData.outputBufEnd = nullptr;
    streamData.inputBufStart = nullptr;
    streamData.inputBufEnd = nullptr;
    streamData.inputBufSize = 0;
    streamData.outputBufSize = 0;
    streamData.refresh = false;
}

} // namespace common
} // namespace lbug
