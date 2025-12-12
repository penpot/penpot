#include "common/assert.h"
#include "common/copier_config/file_scan_info.h"
#include "common/string_utils.h"

namespace lbug {
namespace common {

FileType FileTypeUtils::getFileTypeFromExtension(std::string_view extension) {
    if (extension == ".csv") {
        return FileType::CSV;
    }
    if (extension == ".parquet") {
        return FileType::PARQUET;
    }
    if (extension == ".npy") {
        return FileType::NPY;
    }
    return FileType::UNKNOWN;
}

std::string FileTypeUtils::toString(FileType fileType) {
    switch (fileType) {
    case FileType::UNKNOWN: {
        return "UNKNOWN";
    }
    case FileType::CSV: {
        return "CSV";
    }
    case FileType::PARQUET: {
        return "PARQUET";
    }
    case FileType::NPY: {
        return "NPY";
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

FileType FileTypeUtils::fromString(std::string fileType) {
    fileType = common::StringUtils::getUpper(fileType);
    if (fileType == "CSV") {
        return FileType::CSV;
    } else if (fileType == "PARQUET") {
        return FileType::PARQUET;
    } else if (fileType == "NPY") {
        return FileType::NPY;
    } else {
        return FileType::UNKNOWN;
        // throw BinderException(stringFormat("Unsupported file type: {}.", fileType));
    }
}

} // namespace common
} // namespace lbug
