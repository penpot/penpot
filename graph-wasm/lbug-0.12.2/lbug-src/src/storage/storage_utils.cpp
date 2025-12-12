#include "storage/storage_utils.h"

#include <filesystem>

#include "common/null_buffer.h"
#include "common/string_format.h"
#include "common/types/ku_list.h"
#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "main/settings.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

std::string StorageUtils::getColumnName(const std::string& propertyName, ColumnType type,
    const std::string& prefix) {
    switch (type) {
    case ColumnType::DATA: {
        return stringFormat("{}_data", propertyName);
    }
    case ColumnType::NULL_MASK: {
        return stringFormat("{}_null", propertyName);
    }
    case ColumnType::INDEX: {
        return stringFormat("{}_index", propertyName);
    }
    case ColumnType::OFFSET: {
        return stringFormat("{}_offset", propertyName);
    }
    case ColumnType::CSR_OFFSET: {
        return stringFormat("{}_csr_offset", prefix);
    }
    case ColumnType::CSR_LENGTH: {
        return stringFormat("{}_csr_length", prefix);
    }
    case ColumnType::STRUCT_CHILD: {
        return stringFormat("{}_{}_child", propertyName, prefix);
    }
    default: {
        if (prefix.empty()) {
            return propertyName;
        }
        return stringFormat("{}_{}", prefix, propertyName);
    }
    }
}

std::string StorageUtils::expandPath(const main::ClientContext* context, const std::string& path) {
    if (main::DBConfig::isDBPathInMemory(path)) {
        return path;
    }
    auto fullPath = path;
    // Handle '~' for home directory expansion
    if (path.starts_with('~')) {
        fullPath =
            context->getCurrentSetting(main::HomeDirectorySetting::name).getValue<std::string>() +
            fullPath.substr(1);
    }
    // Normalize the path to resolve '.' and '..'
    std::filesystem::path normalizedPath = std::filesystem::absolute(fullPath).lexically_normal();
    return normalizedPath.string();
}

uint32_t StorageUtils::getDataTypeSize(const LogicalType& type) {
    switch (type.getPhysicalType()) {
    case PhysicalTypeID::STRING: {
        return sizeof(ku_string_t);
    }
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        return sizeof(ku_list_t);
    }
    case PhysicalTypeID::STRUCT: {
        uint32_t size = 0;
        const auto fieldsTypes = StructType::getFieldTypes(type);
        for (const auto& fieldType : fieldsTypes) {
            size += getDataTypeSize(*fieldType);
        }
        size += NullBuffer::getNumBytesForNullValues(fieldsTypes.size());
        return size;
    }
    default: {
        return PhysicalTypeUtils::getFixedTypeSize(type.getPhysicalType());
    }
    }
}

} // namespace storage
} // namespace lbug
