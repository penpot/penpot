#include "common/serializer/buffered_file.h"

#include <cstring>

#include "common/assert.h"
#include "common/exception/runtime.h"
#include "common/file_system/file_info.h"
#include "common/system_config.h"

namespace lbug {
namespace common {

static constexpr uint64_t BUFFER_SIZE = LBUG_PAGE_SIZE;

BufferedFileWriter::BufferedFileWriter(FileInfo& fileInfo)
    : buffer(std::make_unique<uint8_t[]>(BUFFER_SIZE)), fileOffset(0), bufferOffset(0),
      fileInfo(fileInfo) {}

BufferedFileWriter::~BufferedFileWriter() {
    flush();
}

void BufferedFileWriter::write(const uint8_t* data, uint64_t size) {
    if (size > BUFFER_SIZE) {
        flush();
        fileInfo.writeFile(data, size, fileOffset);
        fileOffset += size;
        return;
    }
    KU_ASSERT(size <= BUFFER_SIZE);
    if (bufferOffset + size <= BUFFER_SIZE) {
        memcpy(&buffer[bufferOffset], data, size);
        bufferOffset += size;
    } else {
        auto toCopy = BUFFER_SIZE - bufferOffset;
        memcpy(&buffer[bufferOffset], data, toCopy);
        bufferOffset += toCopy;
        flush();
        auto remaining = size - toCopy;
        memcpy(buffer.get(), data + toCopy, remaining);
        bufferOffset += remaining;
    }
}

void BufferedFileWriter::clear() {
    fileInfo.truncate(0);
    resetOffsets();
}

void BufferedFileWriter::flush() {
    if (bufferOffset == 0) {
        return;
    }
    fileInfo.writeFile(buffer.get(), bufferOffset, fileOffset);
    fileOffset += bufferOffset;
    bufferOffset = 0;
    memset(buffer.get(), 0, BUFFER_SIZE);
}

void BufferedFileWriter::sync() {
    fileInfo.syncFile();
}

uint64_t BufferedFileWriter::getSize() const {
    return fileInfo.getFileSize() + bufferOffset;
}

BufferedFileReader::BufferedFileReader(FileInfo& fileInfo)
    : buffer(std::make_unique<uint8_t[]>(BUFFER_SIZE)), fileOffset(0), bufferOffset(0),
      fileInfo(fileInfo), bufferSize{0} {
    fileSize = this->fileInfo.getFileSize();
    readNextPage();
}

void BufferedFileReader::read(uint8_t* data, uint64_t size) {
    if (size > BUFFER_SIZE) {
        // Clear read buffer.
        fileOffset -= bufferSize;
        fileOffset += bufferOffset;
        fileInfo.readFromFile(data, size, fileOffset);
        fileOffset += size;
        bufferOffset = bufferSize;
    } else if (bufferOffset + size <= bufferSize) {
        memcpy(data, &buffer[bufferOffset], size);
        bufferOffset += size;
    } else {
        auto toCopy = bufferSize - bufferOffset;
        memcpy(data, &buffer[bufferOffset], toCopy);
        bufferOffset += toCopy;
        readNextPage();
        auto remaining = size - toCopy;
        memcpy(data + toCopy, buffer.get(), remaining);
        bufferOffset += remaining;
    }
}

bool BufferedFileReader::finished() {
    return bufferOffset >= bufferSize && fileSize <= fileOffset;
}

void BufferedFileReader::readNextPage() {
    if (fileSize <= fileOffset) {
        throw RuntimeException(
            stringFormat("Reading past the end of the file {} with size {} at offset {}",
                fileInfo.path, fileSize, fileOffset));
    }
    bufferSize = std::min(fileSize - fileOffset, BUFFER_SIZE);
    fileInfo.readFromFile(buffer.get(), bufferSize, fileOffset);
    fileOffset += bufferSize;
    bufferOffset = 0;
}

} // namespace common
} // namespace lbug
