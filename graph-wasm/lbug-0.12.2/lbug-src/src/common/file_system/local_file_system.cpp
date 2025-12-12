#include "common/file_system/local_file_system.h"

#include "common/assert.h"
#include "common/exception/io.h"
#include "common/string_format.h"
#include "common/string_utils.h"
#include "common/system_message.h"
#include "glob/glob.hpp"
#include "main/client_context.h"
#include "main/settings.h"

#if defined(_WIN32)
#include <sys/stat.h>

#include "common/windows_utils.h"
#include <fileapi.h>
#include <io.h>
#include <windows.h>
#else
#include "sys/stat.h"
#include <unistd.h>
#endif

#include <fcntl.h>

#include <cstring>

#include "storage/storage_utils.h"

namespace lbug {
namespace common {

LocalFileInfo::~LocalFileInfo() {
#ifdef _WIN32
    if (handle != nullptr) {
        CloseHandle((HANDLE)handle);
    }
#else
    if (fd != -1) {
        close(fd);
    }
#endif
}

static void validateFileFlags(uint8_t flags) {
    const bool isRead = flags & FileFlags::READ_ONLY;
    const bool isWrite = flags & FileFlags::WRITE;
    KU_UNUSED(isRead);
    KU_UNUSED(isWrite);
    // Require either READ or WRITE (or both).
    KU_ASSERT(isRead || isWrite);
    // CREATE flags require writing.
    KU_ASSERT(isWrite || !(flags & FileFlags::CREATE_IF_NOT_EXISTS));
    KU_ASSERT(isWrite || !(flags & FileFlags::CREATE_AND_TRUNCATE_IF_EXISTS));
    // CREATE_IF_NOT_EXISTS and CREATE_AND_TRUNCATE_IF_EXISTS flags cannot be combined.
    KU_ASSERT(!(flags & FileFlags::CREATE_IF_NOT_EXISTS &&
                flags & FileFlags::CREATE_AND_TRUNCATE_IF_EXISTS));
}

std::unique_ptr<FileInfo> LocalFileSystem::openFile(const std::string& path, FileOpenFlags flags,
    main::ClientContext* context) {
    auto fullPath = expandPath(context, path);
    auto fileFlags = flags.flags;
    validateFileFlags(fileFlags);

    int openFlags = 0;
    bool readMode = fileFlags & FileFlags::READ_ONLY;
    bool writeMode = fileFlags & FileFlags::WRITE;
    if (readMode && writeMode) {
        openFlags = O_RDWR;
    } else if (readMode) {
        openFlags = O_RDONLY;
    } else if (writeMode) {
        openFlags = O_WRONLY;
    } else {
        // LCOV_EXCL_START
        throw InternalException("READ, WRITE or both should be specified when opening a file.");
        // LCOV_EXCL_STOP
    }
    if (writeMode) {
        KU_ASSERT(fileFlags & FileFlags::WRITE);
        if (fileFlags & FileFlags::CREATE_IF_NOT_EXISTS) {
            openFlags |= O_CREAT;
        } else if (fileFlags & FileFlags::CREATE_AND_TRUNCATE_IF_EXISTS) {
            openFlags |= O_CREAT | O_TRUNC;
        }
    }

#if defined(_WIN32)
    auto dwDesiredAccess = 0ul;
    int dwCreationDisposition;
    if (fileFlags & FileFlags::CREATE_IF_NOT_EXISTS) {
        dwCreationDisposition = OPEN_ALWAYS;
    } else if (fileFlags & FileFlags::CREATE_AND_TRUNCATE_IF_EXISTS) {
        dwCreationDisposition = CREATE_ALWAYS;
    } else {
        dwCreationDisposition = OPEN_EXISTING;
    }
    auto dwShareMode = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
    if (openFlags & (O_CREAT | O_WRONLY | O_RDWR)) {
        dwDesiredAccess |= GENERIC_WRITE;
    }
    // O_RDONLY is 0 in practice, so openFlags & (O_RDONLY | O_RDWR) doesn't work.
    if (!(openFlags & O_WRONLY)) {
        dwDesiredAccess |= GENERIC_READ;
    }
    if (openFlags & FileFlags::BINARY) {
        dwDesiredAccess |= _O_BINARY;
    }

    HANDLE handle = CreateFileA(fullPath.c_str(), dwDesiredAccess, dwShareMode, nullptr,
        dwCreationDisposition, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        throw IOException(stringFormat("Cannot open file. path: {} - Error {}: {}", fullPath,
            GetLastError(), std::system_category().message(GetLastError())));
    }
    if (flags.lockType != FileLockType::NO_LOCK) {
        DWORD dwFlags = flags.lockType == FileLockType::READ_LOCK ?
                            LOCKFILE_FAIL_IMMEDIATELY :
                            LOCKFILE_FAIL_IMMEDIATELY | LOCKFILE_EXCLUSIVE_LOCK;
        OVERLAPPED overlapped = {0};
        overlapped.Offset = 0;
        BOOL rc = LockFileEx(handle, dwFlags, 0 /*reserved*/, 1 /*numBytesLow*/, 0 /*numBytesHigh*/,
            &overlapped);
        if (!rc) {
            throw IOException(
                "Could not set lock on file : " + fullPath + "\n" +
                "See the docs: https://docs.ladybugdb.com/concurrency for more information.");
        }
    }
    return std::make_unique<LocalFileInfo>(fullPath, handle, this);
#else
    int fd = open(fullPath.c_str(), openFlags, 0644);
    if (fd == -1) {
        throw IOException(stringFormat("Cannot open file {}: {}", fullPath, posixErrMessage()));
    }
    if (flags.lockType != FileLockType::NO_LOCK) {
        struct flock fl {};
        memset(&fl, 0, sizeof fl);
        fl.l_type = flags.lockType == FileLockType::READ_LOCK ? F_RDLCK : F_WRLCK;
        fl.l_whence = SEEK_SET;
        fl.l_start = 0;
        fl.l_len = 0;
        int rc = fcntl(fd, F_SETLK, &fl);
        if (rc == -1) {
            throw IOException(
                "Could not set lock on file : " + fullPath + "\n" +
                "See the docs: https://docs.ladybugdb.com/concurrency for more information.");
        }
    }
    return std::make_unique<LocalFileInfo>(fullPath, fd, this);
#endif
}

std::vector<std::string> LocalFileSystem::glob(main::ClientContext* context,
    const std::string& path) const {
    if (path.empty()) {
        return std::vector<std::string>();
    }
    std::vector<std::string> pathsToGlob;
    if (path[0] == '/' || (std::isalpha(path[0]) && path[1] == ':')) {
        // Note:
        // Unix absolute path starts with '/'
        // Windows absolute path starts with "[DiskID]://"
        pathsToGlob.push_back(path);
    } else if (path[0] == '~') {
        // Expands home directory
        auto homeDirectory =
            context->getCurrentSetting(main::HomeDirectorySetting::name).getValue<std::string>();
        pathsToGlob.push_back(homeDirectory + path.substr(1));
    } else {
        // Relative path to the file search path.
        auto globbedPaths = glob::glob(path);
        if (!globbedPaths.empty()) {
            pathsToGlob.push_back(path);
        } else {
            auto fileSearchPath = context->getCurrentSetting(main::FileSearchPathSetting::name)
                                      .getValue<std::string>();
            if (fileSearchPath != "") {
                auto searchPaths = StringUtils::split(fileSearchPath, ",");
                for (auto& searchPath : searchPaths) {
                    pathsToGlob.push_back(stringFormat("{}/{}", searchPath, path));
                }
            }
        }
    }
    std::vector<std::string> result;
    for (auto& pathToGlob : pathsToGlob) {
        for (auto& resultPath : glob::glob(pathToGlob)) {
            result.emplace_back(resultPath.string());
        }
    }
    return result;
}

void LocalFileSystem::overwriteFile(const std::string& from, const std::string& to) {
    if (!fileOrPathExists(from) || !fileOrPathExists(to)) {
        return;
    }
    std::error_code errorCode;
    if (!std::filesystem::copy_file(from, to, std::filesystem::copy_options::overwrite_existing,
            errorCode)) {
        // LCOV_EXCL_START
        throw IOException(stringFormat("Error copying file {} to {}.  ErrorMessage: {}", from, to,
            errorCode.message()));
        // LCOV_EXCL_STOP
    }
}

void LocalFileSystem::copyFile(const std::string& from, const std::string& to) {
    if (!fileOrPathExists(from)) {
        return;
    }
    std::error_code errorCode;
    if (!std::filesystem::copy_file(from, to, std::filesystem::copy_options::none, errorCode)) {
        // LCOV_EXCL_START
        throw IOException(stringFormat("Error copying file {} to {}.  ErrorMessage: {}", from, to,
            errorCode.message()));
        // LCOV_EXCL_STOP
    }
}

void LocalFileSystem::createDir(const std::string& dir) const {
    try {
        if (std::filesystem::exists(dir)) {
            // LCOV_EXCL_START
            throw IOException(stringFormat("Directory {} already exists.", dir));
            // LCOV_EXCL_STOP
        }
        auto directoryToCreate = dir;
        if (directoryToCreate.ends_with('/')
#if defined(_WIN32)
            || directoryToCreate.ends_with('\\')
#endif
        ) {
            // This is a known issue with std::filesystem::create_directories. (link:
            // https://github.com/llvm/llvm-project/issues/60634). We have to manually remove the
            // last '/' if the path ends with '/'. (Added the second one for windows)
            directoryToCreate = directoryToCreate.substr(0, directoryToCreate.size() - 1);
        }
        std::error_code errCode;
        if (!std::filesystem::create_directories(directoryToCreate, errCode)) {
            // LCOV_EXCL_START
            throw IOException(
                stringFormat("Directory {} cannot be created. Check if it exists and remove it.",
                    directoryToCreate));
            // LCOV_EXCL_STOP
        }
        if (errCode) {
            // LCOV_EXCL_START
            throw IOException(stringFormat("Failed to create directory: {}, error message: {}.",
                dir, errCode.message()));
            // LCOV_EXCL_STOP
        }
    } catch (std::exception& e) {
        // LCOV_EXCL_START
        throw IOException(stringFormat("Failed to create directory {} due to: {}", dir, e.what()));
        // LCOV_EXCL_STOP
    }
}

static std::unordered_set<std::string> getDatabaseFileSet(const std::string& path) {
    std::unordered_set<std::string> result;
    result.insert(storage::StorageUtils::getWALFilePath(path));
    result.insert(storage::StorageUtils::getShadowFilePath(path));
    result.insert(storage::StorageUtils::getTmpFilePath(path));
    return result;
}

static bool isExtensionFile(const main::ClientContext* context, const std::string& path) {
    if (context == nullptr) {
        return false;
    }
    auto extensionDir = context->getExtensionDir();
    std::filesystem::path rel = std::filesystem::relative(path, extensionDir);
    for (const auto& part : rel) {
        if (part == "..") {
            return false;
        }
    }
    return true;
}

void LocalFileSystem::removeFileIfExists(const std::string& path,
    const main::ClientContext* context) {
    if (!fileOrPathExists(path)) {
        return;
    }
    if (!getDatabaseFileSet(dbPath).contains(path) && !isExtensionFile(context, path)) {
        throw IOException(stringFormat(
            "Error: Path {} is not within the allowed list of files to be removed.", path));
    }
    std::error_code errCode;
    bool success = false;
    if (std::filesystem::is_directory(path)) {
        success = std::filesystem::remove_all(path, errCode);
    } else {
        success = std::filesystem::remove(path, errCode);
    }
    if (!success) {
        // LCOV_EXCL_START
        throw IOException(stringFormat("Error removing directory or file {}.  Error Message: {}",
            path, errCode.message()));
        // LCOV_EXCL_STOP
    }
}

bool LocalFileSystem::fileOrPathExists(const std::string& path, main::ClientContext* /*context*/) {
    return std::filesystem::exists(path);
}

#ifndef _WIN32
bool LocalFileSystem::fileExists(const std::string& filename) {
    if (!filename.empty()) {
        if (access(filename.c_str(), 0) == 0) {
            struct stat status = {};
            stat(filename.c_str(), &status);
            if (S_ISREG(status.st_mode)) {
                return true;
            }
        }
    }
    // if any condition fails
    return false;
}
#else
bool LocalFileSystem::fileExists(const std::string& filename) {
    auto unicode_path = WindowsUtils::utf8ToUnicode(filename.c_str());
    const wchar_t* wpath = unicode_path.c_str();
    if (_waccess(wpath, 0) == 0) {
        struct _stati64 status = {};
        _wstati64(wpath, &status);
        if (status.st_mode & _S_IFREG) {
            return true;
        }
    }
    return false;
}
#endif

std::string LocalFileSystem::expandPath(main::ClientContext* context,
    const std::string& path) const {
    auto fullPath = path;
    if (path.starts_with('~')) {
        fullPath =
            context->getCurrentSetting(main::HomeDirectorySetting::name).getValue<std::string>() +
            fullPath.substr(1);
    }
    return fullPath;
}

bool LocalFileSystem::isLocalPath(const std::string& path) {
    return path.rfind("s3://", 0) != 0 && path.rfind("gs://", 0) != 0 &&
           path.rfind("gcs://", 0) != 0 && path.rfind("http://", 0) != 0 &&
           path.rfind("https://", 0) != 0 && path.rfind("az://", 0) != 0 &&
           path.rfind("abfss://", 0) != 0;
}

void LocalFileSystem::readFromFile(FileInfo& fileInfo, void* buffer, uint64_t numBytes,
    uint64_t position) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
    KU_ASSERT(localFileInfo->getFileSize() >= position + numBytes);
#if defined(_WIN32)
    DWORD numBytesRead;
    OVERLAPPED overlapped{0, 0, 0, 0};
    overlapped.Offset = position & 0xffffffff;
    overlapped.OffsetHigh = position >> 32;
    if (!ReadFile((HANDLE)localFileInfo->handle, buffer, numBytes, &numBytesRead, &overlapped)) {
        auto error = GetLastError();
        throw IOException(
            stringFormat("Cannot read from file: {} handle: {} "
                         "numBytesRead: {} numBytesToRead: {} position: {}. Error {}: {}",
                fileInfo.path, (intptr_t)localFileInfo->handle, numBytesRead, numBytes, position,
                error, std::system_category().message(error)));
    }
    if (numBytesRead != numBytes && fileInfo.getFileSize() != position + numBytesRead) {
        throw IOException(stringFormat("Cannot read from file: {} handle: {} "
                                       "numBytesRead: {} numBytesToRead: {} position: {}",
            fileInfo.path, (intptr_t)localFileInfo->handle, numBytesRead, numBytes, position));
    }
#else
    auto numBytesRead = pread(localFileInfo->fd, buffer, numBytes, position);
    if (static_cast<uint64_t>(numBytesRead) != numBytes &&
        localFileInfo->getFileSize() != position + numBytesRead) {
        // LCOV_EXCL_START
        throw IOException(stringFormat("Cannot read from file: {} fileDescriptor: {} "
                                       "numBytesRead: {} numBytesToRead: {} position: {}",
            fileInfo.path, localFileInfo->fd, numBytesRead, numBytes, position));
        // LCOV_EXCL_STOP
    }
#endif
}

int64_t LocalFileSystem::readFile(FileInfo& fileInfo, void* buf, size_t nbyte) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
#if defined(_WIN32)
    DWORD numBytesRead;
    ReadFile((HANDLE)localFileInfo->handle, buf, nbyte, &numBytesRead, nullptr);
    return numBytesRead;
#else
    return read(localFileInfo->fd, buf, nbyte);
#endif
}

void LocalFileSystem::writeFile(FileInfo& fileInfo, const uint8_t* buffer, uint64_t numBytes,
    uint64_t offset) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
    uint64_t remainingNumBytesToWrite = numBytes;
    uint64_t bufferOffset = 0;
    // Split large writes to 1GB at a time
    uint64_t maxBytesToWriteAtOnce = 1ull << 30; // 1ull << 30 = 1G
    while (remainingNumBytesToWrite > 0) {
        uint64_t numBytesToWrite = std::min(remainingNumBytesToWrite, maxBytesToWriteAtOnce);

#if defined(_WIN32)
        DWORD numBytesWritten;
        OVERLAPPED overlapped{0, 0, 0, 0};
        overlapped.Offset = offset & 0xffffffff;
        overlapped.OffsetHigh = offset >> 32;
        if (!WriteFile((HANDLE)localFileInfo->handle, buffer + bufferOffset, numBytesToWrite,
                &numBytesWritten, &overlapped)) {
            auto error = GetLastError();
            throw IOException(
                stringFormat("Cannot write to file. path: {} handle: {} offsetToWrite: {} "
                             "numBytesToWrite: {} numBytesWritten: {}. Error {}: {}.",
                    fileInfo.path, (intptr_t)localFileInfo->handle, offset, numBytesToWrite,
                    numBytesWritten, error, std::system_category().message(error)));
        }
#else
        auto numBytesWritten =
            pwrite(localFileInfo->fd, buffer + bufferOffset, numBytesToWrite, offset);
        if (numBytesWritten != static_cast<int64_t>(numBytesToWrite)) {
            // LCOV_EXCL_START
            throw IOException(
                stringFormat("Cannot write to file. path: {} fileDescriptor: {} offsetToWrite: {} "
                             "numBytesToWrite: {} numBytesWritten: {}. Error: {}",
                    fileInfo.path, localFileInfo->fd, offset, numBytesToWrite, numBytesWritten,
                    posixErrMessage()));
            // LCOV_EXCL_STOP
        }
#endif
        remainingNumBytesToWrite -= numBytesWritten;
        offset += numBytesWritten;
        bufferOffset += numBytesWritten;
    }
}

void LocalFileSystem::syncFile(const FileInfo& fileInfo) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
#if defined(_WIN32)
    // Note that `FlushFileBuffers` returns 0 when fails, while `fsync` returns 0 when succeeds.
    if (FlushFileBuffers((HANDLE)localFileInfo->handle) == 0) {
        auto error = GetLastError();
        throw IOException(stringFormat("Failed to sync file {}. Error {}: {}", fileInfo.path, error,
            std::system_category().message(error)));
    }
#else
#if HAS_FULLFSYNC and defined(__APPLE__)
    // Try F_FULLFSYNC first on macOS/iOS, which is required to guarantee durability past power
    // failures.
    if (fcntl(localFileInfo->fd, F_FULLFSYNC) == 0) {
        return;
    }
    if (errno != ENOTSUP && errno != EINVAL) {
        // LCOV_EXCL_START
        if (errno == EIO) {
            throw IOException("Fatal error: fsync failed!");
        }
        throw IOException(
            stringFormat("Failed to sync file {}: {}", fileInfo.path, posixErrMessage()));
        // LCOV_EXCL_STOP
    }
#endif
    bool syncSuccess = false;
#if HAS_FDATASYNC
    syncSuccess = fdatasync(localFileInfo->fd) == 0; // Only sync file data + essential metadata.
#else
    syncSuccess = fsync(localFileInfo->fd) == 0; // Sync file data + all metadata.
#endif
    if (!syncSuccess) {
        throw IOException(stringFormat("Failed to sync file {}.", fileInfo.path));
    }
#endif
}

int64_t LocalFileSystem::seek(FileInfo& fileInfo, uint64_t offset, int whence) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
#if defined(_WIN32)
    LARGE_INTEGER result;
    LARGE_INTEGER offset_;
    offset_.QuadPart = offset;
    SetFilePointerEx((HANDLE)localFileInfo->handle, offset_, &result, whence);
    return result.QuadPart;
#else
    return lseek(localFileInfo->fd, offset, whence);
#endif
}

void LocalFileSystem::truncate(FileInfo& fileInfo, uint64_t size) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
#if defined(_WIN32)
    auto offsetHigh = (LONG)(size >> 32);
    LONG* offsetHighPtr = NULL;
    if (offsetHigh > 0)
        offsetHighPtr = &offsetHigh;
    if (SetFilePointer((HANDLE)localFileInfo->handle, size & 0xffffffff, offsetHighPtr,
            FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        auto error = GetLastError();
        throw IOException(stringFormat("Cannot set file pointer for file: {} handle: {} "
                                       "new position: {}. Error {}: {}",
            fileInfo.path, (intptr_t)localFileInfo->handle, size, error,
            std::system_category().message(error)));
    }
    if (!SetEndOfFile((HANDLE)localFileInfo->handle)) {
        auto error = GetLastError();
        throw IOException(stringFormat("Cannot truncate file: {} handle: {} "
                                       "size: {}. Error {}: {}",
            fileInfo.path, (intptr_t)localFileInfo->handle, size, error,
            std::system_category().message(error)));
    }
#else
    if (ftruncate(localFileInfo->fd, size) < 0) {
        // LCOV_EXCL_START
        throw IOException(
            stringFormat("Failed to truncate file {}: {}", fileInfo.path, posixErrMessage()));
        // LCOV_EXCL_STOP
    }
#endif
}

uint64_t LocalFileSystem::getFileSize(const FileInfo& fileInfo) const {
    auto localFileInfo = fileInfo.constPtrCast<LocalFileInfo>();
#ifdef _WIN32
    LARGE_INTEGER size;
    if (!GetFileSizeEx((HANDLE)localFileInfo->handle, &size)) {
        auto error = GetLastError();
        throw IOException(stringFormat("Cannot read size of file. path: {} - Error {}: {}",
            fileInfo.path, error, systemErrMessage(error)));
    }
    return size.QuadPart;
#else
    struct stat s {};
    if (fstat(localFileInfo->fd, &s) == -1) {
        throw IOException(stringFormat("Cannot read size of file. path: {} - Error {}: {}",
            fileInfo.path, errno, posixErrMessage()));
    }
    KU_ASSERT(s.st_size >= 0);
    return s.st_size;
#endif
}

} // namespace common
} // namespace lbug
