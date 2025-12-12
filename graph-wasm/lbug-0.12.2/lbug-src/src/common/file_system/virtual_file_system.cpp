#include "common/file_system/virtual_file_system.h"

#include "common/assert.h"
#include "common/exception/io.h"
#include "common/file_system/gzip_file_system.h"
#include "common/file_system/local_file_system.h"
#include "common/string_utils.h"
#include "main/client_context.h"
#include "main/database.h"

namespace lbug {
namespace common {

VirtualFileSystem::VirtualFileSystem() : VirtualFileSystem{""} {}

VirtualFileSystem::VirtualFileSystem(std::string homeDir) {
    defaultFS = std::make_unique<LocalFileSystem>(homeDir);
    compressedFileSystem.emplace(FileCompressionType::GZIP, std::make_unique<GZipFileSystem>());
}

VirtualFileSystem::~VirtualFileSystem() = default;

void VirtualFileSystem::registerFileSystem(std::unique_ptr<FileSystem> fileSystem) {
    subSystems.push_back(std::move(fileSystem));
}

FileCompressionType VirtualFileSystem::autoDetectCompressionType(const std::string& path) {
    if (isGZIPCompressed(path)) {
        return FileCompressionType::GZIP;
    }
    return FileCompressionType::UNCOMPRESSED;
}

std::unique_ptr<FileInfo> VirtualFileSystem::openFile(const std::string& path, FileOpenFlags flags,
    main::ClientContext* context) {
    auto compressionType = flags.compressionType;
    if (compressionType == FileCompressionType::AUTO_DETECT) {
        compressionType = autoDetectCompressionType(path);
    }
    auto fileHandle = findFileSystem(path)->openFile(path, flags, context);
    if (compressionType == FileCompressionType::UNCOMPRESSED) {
        return fileHandle;
    }
    if (flags.flags & FileFlags::WRITE) {
        throw IOException{"Writing to compressed files is not supported yet."};
    }
    if (StringUtils::getLower(getFileExtension(path)) != ".csv") {
        throw IOException{"Lbug currently only supports reading from compressed csv files."};
    }
    return compressedFileSystem.at(compressionType)->openCompressedFile(std::move(fileHandle));
}

std::vector<std::string> VirtualFileSystem::glob(main::ClientContext* context,
    const std::string& path) const {
    return findFileSystem(path)->glob(context, path);
}

void VirtualFileSystem::overwriteFile(const std::string& from, const std::string& to) {
    findFileSystem(from)->overwriteFile(from, to);
}

void VirtualFileSystem::createDir(const std::string& dir) const {
    findFileSystem(dir)->createDir(dir);
}

void VirtualFileSystem::removeFileIfExists(const std::string& path,
    const main::ClientContext* context) {
    findFileSystem(path)->removeFileIfExists(path, context);
}

bool VirtualFileSystem::fileOrPathExists(const std::string& path, main::ClientContext* context) {
    return findFileSystem(path)->fileOrPathExists(path, context);
}

std::string VirtualFileSystem::expandPath(main::ClientContext* context,
    const std::string& path) const {
    return findFileSystem(path)->expandPath(context, path);
}

void VirtualFileSystem::readFromFile(FileInfo& /*fileInfo*/, void* /*buffer*/,
    uint64_t /*numBytes*/, uint64_t /*position*/) const {
    KU_UNREACHABLE;
}

int64_t VirtualFileSystem::readFile(FileInfo& /*fileInfo*/, void* /*buf*/, size_t /*nbyte*/) const {
    KU_UNREACHABLE;
}

void VirtualFileSystem::writeFile(FileInfo& /*fileInfo*/, const uint8_t* /*buffer*/,
    uint64_t /*numBytes*/, uint64_t /*offset*/) const {
    KU_UNREACHABLE;
}

void VirtualFileSystem::syncFile(const FileInfo& fileInfo) const {
    findFileSystem(fileInfo.path)->syncFile(fileInfo);
}

void VirtualFileSystem::cleanUP(main::ClientContext* context) {
    for (auto& subSystem : subSystems) {
        subSystem->cleanUP(context);
    }
    defaultFS->cleanUP(context);
}

bool VirtualFileSystem::handleFileViaFunction(const std::string& path) const {
    return findFileSystem(path)->handleFileViaFunction(path);
}

function::TableFunction VirtualFileSystem::getHandleFunction(const std::string& path) const {
    return findFileSystem(path)->getHandleFunction(path);
}

int64_t VirtualFileSystem::seek(FileInfo& /*fileInfo*/, uint64_t /*offset*/, int /*whence*/) const {
    KU_UNREACHABLE;
}

void VirtualFileSystem::truncate(FileInfo& /*fileInfo*/, uint64_t /*size*/) const {
    KU_UNREACHABLE;
}

uint64_t VirtualFileSystem::getFileSize(const FileInfo& /*fileInfo*/) const {
    KU_UNREACHABLE;
}

FileSystem* VirtualFileSystem::findFileSystem(const std::string& path) const {
    for (auto& subSystem : subSystems) {
        if (subSystem->canHandleFile(path)) {
            return subSystem.get();
        }
    }
    return defaultFS.get();
}

VirtualFileSystem* VirtualFileSystem::GetUnsafe(const main::ClientContext& context) {
    return context.getDatabase()->getVFS();
}

} // namespace common
} // namespace lbug
