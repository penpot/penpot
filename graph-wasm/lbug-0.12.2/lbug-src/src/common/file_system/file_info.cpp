#include "common/file_system/file_info.h"

#include "common/file_system/file_system.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace lbug {
namespace common {

uint64_t FileInfo::getFileSize() const {
    return fileSystem->getFileSize(*this);
}

void FileInfo::readFromFile(void* buffer, uint64_t numBytes, uint64_t position) {
    fileSystem->readFromFile(*this, buffer, numBytes, position);
}

int64_t FileInfo::readFile(void* buf, size_t nbyte) {
    return fileSystem->readFile(*this, buf, nbyte);
}

void FileInfo::writeFile(const uint8_t* buffer, uint64_t numBytes, uint64_t offset) {
    fileSystem->writeFile(*this, buffer, numBytes, offset);
}

void FileInfo::syncFile() const {
    fileSystem->syncFile(*this);
}

int64_t FileInfo::seek(uint64_t offset, int whence) {
    return fileSystem->seek(*this, offset, whence);
}

void FileInfo::reset() {
    fileSystem->reset(*this);
}

void FileInfo::truncate(uint64_t size) {
    fileSystem->truncate(*this, size);
}

bool FileInfo::canPerformSeek() const {
    return fileSystem->canPerformSeek();
}

} // namespace common
} // namespace lbug
