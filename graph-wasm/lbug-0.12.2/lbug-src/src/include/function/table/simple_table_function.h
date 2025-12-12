#pragma once

#include "common/system_config.h"
#include "function/table/table_function.h"

namespace lbug {
namespace function {

struct TableFuncMorsel {
    common::offset_t startOffset;
    common::offset_t endOffset;

    TableFuncMorsel(common::offset_t startOffset, common::offset_t endOffset)
        : startOffset{startOffset}, endOffset{endOffset} {}

    bool hasMoreToOutput() const { return startOffset != common::INVALID_OFFSET; }

    static TableFuncMorsel createInvalidMorsel() {
        return {common::INVALID_OFFSET, common::INVALID_OFFSET};
    }

    uint64_t getMorselSize() const { return endOffset - startOffset; }

    bool isInvalid() const {
        return startOffset == common::INVALID_OFFSET && endOffset == common::INVALID_OFFSET;
    }
};

using simple_internal_table_func = std::function<common::offset_t(const TableFuncMorsel&,
    const TableFuncInput&, common::DataChunk& output)>;

class LBUG_API SimpleTableFuncSharedState : public TableFuncSharedState {
public:
    SimpleTableFuncSharedState() = default;

    explicit SimpleTableFuncSharedState(common::row_idx_t numRows,
        common::offset_t maxMorselSize = common::DEFAULT_VECTOR_CAPACITY)
        : TableFuncSharedState{numRows}, maxMorselSize{maxMorselSize} {}

    virtual TableFuncMorsel getMorsel();

    common::row_idx_t curRowIdx = 0;
    common::offset_t maxMorselSize = common::DEFAULT_VECTOR_CAPACITY;
};

struct LBUG_API SimpleTableFunc {
    static std::unique_ptr<TableFuncSharedState> initSharedState(
        const TableFuncInitSharedStateInput& input);

    static table_func_t getTableFunc(simple_internal_table_func internalTableFunc);
};

struct CurrentSettingFunction final {
    static constexpr const char* name = "CURRENT_SETTING";

    static function_set getFunctionSet();
};

struct CatalogVersionFunction final {
    static constexpr const char* name = "CATALOG_VERSION";

    static function_set getFunctionSet();
};

struct DBVersionFunction final {
    static constexpr const char* name = "DB_VERSION";

    static function_set getFunctionSet();
};

struct ShowTablesFunction final {
    static constexpr const char* name = "SHOW_TABLES";

    static function_set getFunctionSet();
};

struct ShowWarningsFunction final {
    static constexpr const char* name = "SHOW_WARNINGS";

    static function_set getFunctionSet();
};

struct ShowMacrosFunction final {
    static constexpr const char* name = "SHOW_MACROS";

    static function_set getFunctionSet();
};

struct TableInfoFunction final {
    static constexpr const char* name = "TABLE_INFO";

    static function_set getFunctionSet();
};

struct ShowSequencesFunction final {
    static constexpr const char* name = "SHOW_SEQUENCES";

    static function_set getFunctionSet();
};

struct ShowConnectionFunction final {
    static constexpr const char* name = "SHOW_CONNECTION";

    static function_set getFunctionSet();
};

struct StorageInfoFunction final {
    static constexpr const char* name = "STORAGE_INFO";

    static function_set getFunctionSet();
};

struct StatsInfoFunction final {
    static constexpr const char* name = "STATS_INFO";

    static function_set getFunctionSet();
};

struct FreeSpaceInfoFunction final {
    static constexpr const char* name = "FSM_INFO";

    static function_set getFunctionSet();
};

struct BMInfoFunction final {
    static constexpr const char* name = "BM_INFO";

    static function_set getFunctionSet();
};

struct FileInfoFunction final {
    static constexpr const char* name = "FILE_INFO";

    static function_set getFunctionSet();
};

struct ShowAttachedDatabasesFunction final {
    static constexpr const char* name = "SHOW_ATTACHED_DATABASES";

    static function_set getFunctionSet();
};

struct ShowFunctionsFunction final {
    static constexpr const char* name = "SHOW_FUNCTIONS";

    static function_set getFunctionSet();
};

struct ShowLoadedExtensionsFunction final {
    static constexpr const char* name = "SHOW_LOADED_EXTENSIONS";

    static function_set getFunctionSet();
};

struct ShowOfficialExtensionsFunction final {
    static constexpr const char* name = "SHOW_OFFICIAL_EXTENSIONS";

    static function_set getFunctionSet();
};

struct ShowIndexesFunction final {
    static constexpr const char* name = "SHOW_INDEXES";

    static function_set getFunctionSet();
};

struct ShowProjectedGraphsFunction final {
    static constexpr const char* name = "SHOW_PROJECTED_GRAPHS";

    static function_set getFunctionSet();
};

struct ProjectedGraphInfoFunction final {
    static constexpr const char* name = "PROJECTED_GRAPH_INFO";

    static function_set getFunctionSet();
};

// Cache a table column to the transaction local cache.
// Note this is only used for internal purpose, and only supports node tables for now.
struct LocalCacheArrayColumnFunction final {
    static constexpr const char* name = "_CACHE_ARRAY_COLUMN_LOCALLY";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
