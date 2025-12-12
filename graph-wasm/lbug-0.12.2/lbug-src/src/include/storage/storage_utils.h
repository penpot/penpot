#pragma once

#include <cmath>
#include <string>
#include <type_traits>

#include "common/constants.h"
#include "common/system_config.h"
#include "common/types/types.h"
#include <concepts>

namespace lbug {
namespace storage {

struct PageCursor {
    PageCursor() : PageCursor{UINT32_MAX, UINT16_MAX} {};
    PageCursor(common::page_idx_t pageIdx, uint32_t posInPage)
        : pageIdx{pageIdx}, elemPosInPage{posInPage} {};

    void nextPage() {
        pageIdx++;
        elemPosInPage = 0;
    }

    common::page_idx_t pageIdx;
    // Larger than necessary, but PageCursor is directly written to disk
    // and adding an explicit padding field messes with structured bindings
    uint32_t elemPosInPage;
};
static_assert(std::has_unique_object_representations_v<PageCursor>);

template<typename T>
concept NumericType = std::is_integral_v<T> || std::floating_point<T>;

class StorageUtils {
public:
    enum class ColumnType {
        DEFAULT = 0,
        INDEX = 1,  // This is used for index columns in STRING columns.
        OFFSET = 2, // This is used for offset columns in LIST and STRING columns.
        DATA = 3,   // This is used for data columns in LIST and STRING columns.
        CSR_OFFSET = 4,
        CSR_LENGTH = 5,
        STRUCT_CHILD = 6,
        NULL_MASK = 7,
    };

    template<NumericType T1, NumericType T2>
    static uint64_t divideAndRoundUpTo(T1 v1, T2 v2) {
        return std::ceil(static_cast<double>(v1) / static_cast<double>(v2));
    }

    static std::string getColumnName(const std::string& propertyName, ColumnType type,
        const std::string& prefix);

    static common::offset_t getStartOffsetOfNodeGroup(common::node_group_idx_t nodeGroupIdx) {
        return nodeGroupIdx << common::StorageConfig::NODE_GROUP_SIZE_LOG2;
    }
    static common::node_group_idx_t getNodeGroupIdx(common::offset_t nodeOffset) {
        return nodeOffset >> common::StorageConfig::NODE_GROUP_SIZE_LOG2;
    }
    static std::pair<common::node_group_idx_t, common::offset_t> getNodeGroupIdxAndOffsetInChunk(
        common::offset_t nodeOffset) {
        auto nodeGroupIdx = getNodeGroupIdx(nodeOffset);
        auto offsetInChunk = nodeOffset - getStartOffsetOfNodeGroup(nodeGroupIdx);
        return std::make_pair(nodeGroupIdx, offsetInChunk);
    }

    static std::string getWALFilePath(const std::string& path) {
        return common::stringFormat("{}.{}", path, common::StorageConstants::WAL_FILE_SUFFIX);
    }
    static std::string getShadowFilePath(const std::string& path) {
        return common::stringFormat("{}.{}", path, common::StorageConstants::SHADOWING_SUFFIX);
    }
    static std::string getTmpFilePath(const std::string& path) {
        return common::stringFormat("{}.{}", path, common::StorageConstants::TEMP_FILE_SUFFIX);
    }

    static std::string expandPath(const main::ClientContext* context, const std::string& path);

    // Note: This is a relatively slow function because of division and mod and making std::pair.
    // It is not meant to be used in performance critical code path.
    static std::pair<uint64_t, uint64_t> getQuotientRemainder(uint64_t i, uint64_t divisor) {
        return std::make_pair(i / divisor, i % divisor);
    }

    static uint32_t getDataTypeSize(const common::LogicalType& type);
};

} // namespace storage
} // namespace lbug
