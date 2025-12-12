#pragma once

#include <string>
#include <vector>

#include "common/case_insensitive_map.h"
#include "common/copy_constructors.h"
#include "common/types/value/value.h"

namespace lbug {
namespace common {

enum class FileType : uint8_t {
    UNKNOWN = 0,
    CSV = 1,
    PARQUET = 2,
    NPY = 3,
};

struct FileTypeInfo {
    FileType fileType = FileType::UNKNOWN;
    std::string fileTypeStr;
};

struct FileTypeUtils {
    static FileType getFileTypeFromExtension(std::string_view extension);
    static std::string toString(FileType fileType);
    static FileType fromString(std::string fileType);
};

struct FileScanInfo {
    static constexpr const char* FILE_FORMAT_OPTION_NAME = "FILE_FORMAT";

    FileTypeInfo fileTypeInfo;
    std::vector<std::string> filePaths;
    case_insensitive_map_t<Value> options;

    FileScanInfo() : fileTypeInfo{FileType::UNKNOWN, ""} {}
    FileScanInfo(FileTypeInfo fileTypeInfo, std::vector<std::string> filePaths)
        : fileTypeInfo{std::move(fileTypeInfo)}, filePaths{std::move(filePaths)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(FileScanInfo);

    uint32_t getNumFiles() const { return filePaths.size(); }
    std::string getFilePath(idx_t fileIdx) const {
        KU_ASSERT(fileIdx < getNumFiles());
        return filePaths[fileIdx];
    }

    template<typename T>
    T getOption(std::string optionName, T defaultValue) const {
        const auto optionIt = options.find(optionName);
        if (optionIt != options.end()) {
            return optionIt->second.getValue<T>();
        } else {
            return defaultValue;
        }
    }

private:
    FileScanInfo(const FileScanInfo& other)
        : fileTypeInfo{other.fileTypeInfo}, filePaths{other.filePaths}, options{other.options} {}
};

} // namespace common
} // namespace lbug
