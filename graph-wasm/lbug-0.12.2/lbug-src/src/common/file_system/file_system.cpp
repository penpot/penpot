#include "common/file_system/file_system.h"

#include "common/string_utils.h"

namespace lbug {
namespace common {

void FileSystem::overwriteFile(const std::string& /*from*/, const std::string& /*to*/) {
    KU_UNREACHABLE;
}

void FileSystem::copyFile(const std::string& /*from*/, const std::string& /*to*/) {
    KU_UNREACHABLE;
}

void FileSystem::createDir(const std::string& /*dir*/) const {
    KU_UNREACHABLE;
}

void FileSystem::removeFileIfExists(const std::string&, const main::ClientContext* /*context*/) {
    KU_UNREACHABLE;
}

bool FileSystem::fileOrPathExists(const std::string& /*path*/, main::ClientContext* /*context*/) {
    KU_UNREACHABLE;
}

std::string FileSystem::expandPath(main::ClientContext* /*context*/,
    const std::string& path) const {
    return path;
}

std::string FileSystem::joinPath(const std::string& base, const std::string& part) {
    return base + "/" + part;
}

std::string FileSystem::getFileExtension(const std::filesystem::path& path) {
    auto extension = path.extension();
    if (isCompressedFile(path)) {
        extension = path.stem().extension();
    }
    return extension.string();
}

bool FileSystem::isCompressedFile(const std::filesystem::path& path) {
    return isGZIPCompressed(path);
}

std::string FileSystem::getFileName(const std::filesystem::path& path) {
    return path.filename().string();
}

void FileSystem::writeFile(FileInfo& /*fileInfo*/, const uint8_t* /*buffer*/, uint64_t /*numBytes*/,
    uint64_t /*offset*/) const {
    KU_UNREACHABLE;
}

void FileSystem::truncate(FileInfo& /*fileInfo*/, uint64_t /*size*/) const {
    KU_UNREACHABLE;
}

void FileSystem::reset(FileInfo& fileInfo) {
    fileInfo.seek(0, SEEK_SET);
}

bool FileSystem::isGZIPCompressed(const std::filesystem::path& path) {
    auto extensionLowerCase = StringUtils::getLower(path.extension().string());
    return extensionLowerCase == ".gz" || extensionLowerCase == ".gzip";
}

} // namespace common
} // namespace lbug
