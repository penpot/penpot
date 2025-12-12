#pragma once

#include <filesystem>
#include <vector>

#include "common/assert.h"
#include "common/cast.h"
#include "file_info.h"

namespace lbug {
namespace main {
class ClientContext;
} // namespace main

namespace common {

enum class FileLockType : uint8_t { NO_LOCK = 0, READ_LOCK = 1, WRITE_LOCK = 2 };

enum class FileCompressionType : uint8_t { AUTO_DETECT = 0, UNCOMPRESSED = 1, GZIP = 2, ZSTD = 3 };

struct FileFlags {
    static constexpr uint8_t READ_ONLY = 1 << 0;
    static constexpr uint8_t WRITE = 1 << 1;
    // Create file if not exists, can only be used together with WRITE
    static constexpr uint8_t CREATE_IF_NOT_EXISTS = 1 << 3;
    // Always create a new file. If a file exists, the file is truncated. Cannot be used together
    // with CREATE_IF_NOT_EXISTS.
    static constexpr uint8_t CREATE_AND_TRUNCATE_IF_EXISTS = 1 << 4;
    // Temporary file that is not persisted to disk.
    static constexpr uint8_t TEMPORARY = 1 << 5;
#ifdef _WIN32
    // Only used in windows to open files in binary mode.
    static constexpr uint8_t BINARY = 1 << 5;
#endif
};

struct FileOpenFlags {
    int flags;
    FileLockType lockType = FileLockType::NO_LOCK;
    FileCompressionType compressionType = FileCompressionType::AUTO_DETECT;

    explicit FileOpenFlags(int flags) : flags{flags} {}
    explicit FileOpenFlags(int flags, FileLockType lockType) : flags{flags}, lockType{lockType} {}
};

class LBUG_API FileSystem {
    friend struct FileInfo;

public:
    FileSystem() = default;

    explicit FileSystem(std::string homeDir) : dbPath(std::move(homeDir)) {}

    virtual ~FileSystem() = default;

    virtual std::unique_ptr<FileInfo> openFile(const std::string& /*path*/, FileOpenFlags /*flags*/,
        main::ClientContext* /*context*/ = nullptr) {
        KU_UNREACHABLE;
    }

    virtual std::vector<std::string> glob(main::ClientContext* /*context*/,
        const std::string& /*path*/) const {
        KU_UNREACHABLE;
    }

    virtual void overwriteFile(const std::string& from, const std::string& to);

    virtual void copyFile(const std::string& from, const std::string& to);

    virtual void createDir(const std::string& dir) const;

    virtual void removeFileIfExists(const std::string& path,
        const main::ClientContext* context = nullptr);

    virtual bool fileOrPathExists(const std::string& path, main::ClientContext* context = nullptr);

    virtual std::string expandPath(main::ClientContext* context, const std::string& path) const;

    static std::string joinPath(const std::string& base, const std::string& part);

    static std::string getFileExtension(const std::filesystem::path& path);

    static bool isCompressedFile(const std::filesystem::path& path);

    static std::string getFileName(const std::filesystem::path& path);

    virtual bool canHandleFile(const std::string_view /*path*/) const { KU_UNREACHABLE; }

    virtual void syncFile(const FileInfo& fileInfo) const = 0;

    virtual bool canPerformSeek() const { return true; }

    virtual bool handleFileViaFunction(const std::string& /*path*/) const { return false; }

    virtual function::TableFunction getHandleFunction(const std::string& /*path*/) const {
        KU_UNREACHABLE;
    }

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

    virtual void cleanUP(main::ClientContext* /*context*/) {}

protected:
    virtual void readFromFile(FileInfo& fileInfo, void* buffer, uint64_t numBytes,
        uint64_t position) const = 0;

    virtual int64_t readFile(FileInfo& fileInfo, void* buf, size_t numBytes) const = 0;

    virtual void writeFile(FileInfo& fileInfo, const uint8_t* buffer, uint64_t numBytes,
        uint64_t offset) const;

    virtual int64_t seek(FileInfo& fileInfo, uint64_t offset, int whence) const = 0;

    virtual void reset(FileInfo& fileInfo);

    virtual void truncate(FileInfo& fileInfo, uint64_t size) const;

    virtual uint64_t getFileSize(const FileInfo& fileInfo) const = 0;

    static bool isGZIPCompressed(const std::filesystem::path& path);

    std::string dbPath;
};

} // namespace common
} // namespace lbug
