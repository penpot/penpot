#pragma once

#include <array>
#include <cstdint>
#include <string_view>

#include "common/array_utils.h"
#include "common/types/types.h"

namespace lbug {
namespace common {

extern const char* LBUG_VERSION;

constexpr double DEFAULT_HT_LOAD_FACTOR = 1.5;

// This is the default thread sleep time we use when a thread,
// e.g., a worker thread is in TaskScheduler, needs to block.
constexpr uint64_t THREAD_SLEEP_TIME_WHEN_WAITING_IN_MICROS = 500;

constexpr uint64_t DEFAULT_CHECKPOINT_WAIT_TIMEOUT_IN_MICROS = 5000000;

// Note that some places use std::bit_ceil to calculate resizes,
// which won't work for values other than 2. If this is changed, those will need to be updated
constexpr uint64_t CHUNK_RESIZE_RATIO = 2;

struct InternalKeyword {
    static constexpr char ANONYMOUS[] = "";
    static constexpr char ID[] = "_ID";
    static constexpr char LABEL[] = "_LABEL";
    static constexpr char SRC[] = "_SRC";
    static constexpr char DST[] = "_DST";
    static constexpr char DIRECTION[] = "_DIRECTION";
    static constexpr char LENGTH[] = "_LENGTH";
    static constexpr char NODES[] = "_NODES";
    static constexpr char RELS[] = "_RELS";
    static constexpr char STAR[] = "*";
    static constexpr char PLACE_HOLDER[] = "_PLACE_HOLDER";
    static constexpr char MAP_KEY[] = "KEY";
    static constexpr char MAP_VALUE[] = "VALUE";

    static constexpr std::string_view ROW_OFFSET = "_row_offset";
    static constexpr std::string_view SRC_OFFSET = "_src_offset";
    static constexpr std::string_view DST_OFFSET = "_dst_offset";
};

enum PageSizeClass : uint8_t {
    REGULAR_PAGE = 0,
    TEMP_PAGE = 1,
};

struct BufferPoolConstants {
    // If a user does not specify a max size for BM, we by default set the max size of BM to
    // maxPhyMemSize * DEFAULT_PHY_MEM_SIZE_RATIO_FOR_BM.
    static constexpr double DEFAULT_PHY_MEM_SIZE_RATIO_FOR_BM = 0.8;
// The default max size for a VMRegion.
#ifdef __32BIT__
    static constexpr uint64_t DEFAULT_VM_REGION_MAX_SIZE = (uint64_t)1 << 30; // (1GB)
#elif defined(__ANDROID__)
    static constexpr uint64_t DEFAULT_VM_REGION_MAX_SIZE = (uint64_t)1 << 38; // (256GB)
#else
    static constexpr uint64_t DEFAULT_VM_REGION_MAX_SIZE = static_cast<uint64_t>(1) << 43; // (8TB)
#endif
};

struct StorageConstants {
    static constexpr page_idx_t DB_HEADER_PAGE_IDX = 0;
    static constexpr char WAL_FILE_SUFFIX[] = "wal";
    static constexpr char SHADOWING_SUFFIX[] = "shadow";
    static constexpr char TEMP_FILE_SUFFIX[] = "tmp";

    // The number of pages that we add at one time when we need to grow a file.
    static constexpr uint64_t PAGE_GROUP_SIZE_LOG2 = 10;
    static constexpr uint64_t PAGE_GROUP_SIZE = static_cast<uint64_t>(1) << PAGE_GROUP_SIZE_LOG2;
    static constexpr uint64_t PAGE_IDX_IN_GROUP_MASK =
        (static_cast<uint64_t>(1) << PAGE_GROUP_SIZE_LOG2) - 1;

    static constexpr double PACKED_CSR_DENSITY = 0.8;
    static constexpr double LEAF_HIGH_CSR_DENSITY = 1.0;

    static constexpr uint64_t MAX_NUM_ROWS_IN_TABLE = static_cast<uint64_t>(1) << 62;
};

struct TableOptionConstants {
    static constexpr char REL_STORAGE_DIRECTION_OPTION[] = "STORAGE_DIRECTION";
};

// Hash Index Configurations
struct HashIndexConstants {
    static constexpr uint16_t SLOT_CAPACITY_BYTES = 256;
    static constexpr uint64_t NUM_HASH_INDEXES_LOG2 = 8;
    static constexpr uint64_t NUM_HASH_INDEXES = 1 << NUM_HASH_INDEXES_LOG2;
};

struct CopyConstants {
    // Initial size of buffer for CSV Reader.
    static constexpr uint64_t INITIAL_BUFFER_SIZE = 16384;
    // This means that we will usually read the entirety of the contents of the file we need for a
    // block in one read request. It is also very small, which means we can parallelize small files
    // efficiently.
    static constexpr uint64_t PARALLEL_BLOCK_SIZE = INITIAL_BUFFER_SIZE / 2;

    static constexpr const char* IGNORE_ERRORS_OPTION_NAME = "IGNORE_ERRORS";

    static constexpr const char* FROM_OPTION_NAME = "FROM";
    static constexpr const char* TO_OPTION_NAME = "TO";

    static constexpr const char* BOOL_CSV_PARSING_OPTIONS[] = {"HEADER", "PARALLEL",
        "LIST_UNBRACED", "AUTODETECT", "AUTO_DETECT", CopyConstants::IGNORE_ERRORS_OPTION_NAME};
    static constexpr bool DEFAULT_CSV_HAS_HEADER = false;
    static constexpr bool DEFAULT_CSV_PARALLEL = true;

    // Default configuration for csv file parsing
    static constexpr const char* STRING_CSV_PARSING_OPTIONS[] = {"ESCAPE", "DELIM", "DELIMITER",
        "QUOTE"};
    static constexpr char DEFAULT_CSV_ESCAPE_CHAR = '"';
    static constexpr char DEFAULT_CSV_DELIMITER = ',';
    static constexpr bool DEFAULT_CSV_ALLOW_UNBRACED_LIST = false;
    static constexpr char DEFAULT_CSV_QUOTE_CHAR = '"';
    static constexpr char DEFAULT_CSV_LIST_BEGIN_CHAR = '[';
    static constexpr char DEFAULT_CSV_LIST_END_CHAR = ']';
    static constexpr bool DEFAULT_IGNORE_ERRORS = false;
    static constexpr bool DEFAULT_CSV_AUTO_DETECT = true;
    static constexpr bool DEFAULT_CSV_SET_DIALECT = false;
    static constexpr std::array DEFAULT_CSV_DELIMITER_SEARCH_SPACE = {',', ';', '\t', '|'};
    static constexpr std::array DEFAULT_CSV_QUOTE_SEARCH_SPACE = {'"', '\''};
    static constexpr std::array DEFAULT_CSV_ESCAPE_SEARCH_SPACE = {'"', '\\', '\''};
    static constexpr std::array DEFAULT_CSV_NULL_STRINGS = {""};

    static constexpr const char* INT_CSV_PARSING_OPTIONS[] = {"SKIP", "SAMPLE_SIZE"};
    static constexpr uint64_t DEFAULT_CSV_SKIP_NUM = 0;
    static constexpr uint64_t DEFAULT_CSV_TYPE_DEDUCTION_SAMPLE_SIZE = 256;

    static constexpr const char* LIST_CSV_PARSING_OPTIONS[] = {"NULL_STRINGS"};

    // metadata columns used to populate CSV warnings
    static constexpr std::array SHARED_WARNING_DATA_COLUMN_NAMES = {"blockIdx", "offsetInBlock",
        "startByteOffset", "endByteOffset"};
    static constexpr std::array SHARED_WARNING_DATA_COLUMN_TYPES = {LogicalTypeID::UINT64,
        LogicalTypeID::UINT32, LogicalTypeID::UINT64, LogicalTypeID::UINT64};
    static constexpr column_id_t SHARED_WARNING_DATA_NUM_COLUMNS =
        SHARED_WARNING_DATA_COLUMN_NAMES.size();

    static constexpr std::array CSV_SPECIFIC_WARNING_DATA_COLUMN_NAMES = {"fileIdx"};
    static constexpr std::array CSV_SPECIFIC_WARNING_DATA_COLUMN_TYPES = {LogicalTypeID::UINT32};

    static constexpr std::array CSV_WARNING_DATA_COLUMN_NAMES =
        arrayConcat(SHARED_WARNING_DATA_COLUMN_NAMES, CSV_SPECIFIC_WARNING_DATA_COLUMN_NAMES);
    static constexpr std::array CSV_WARNING_DATA_COLUMN_TYPES =
        arrayConcat(SHARED_WARNING_DATA_COLUMN_TYPES, CSV_SPECIFIC_WARNING_DATA_COLUMN_TYPES);
    static constexpr column_id_t CSV_WARNING_DATA_NUM_COLUMNS =
        CSV_WARNING_DATA_COLUMN_NAMES.size();
    static_assert(CSV_WARNING_DATA_NUM_COLUMNS == CSV_WARNING_DATA_COLUMN_TYPES.size());

    static constexpr column_id_t MAX_NUM_WARNING_DATA_COLUMNS = CSV_WARNING_DATA_NUM_COLUMNS;
};

struct PlannerKnobs {
    static constexpr double NON_EQUALITY_PREDICATE_SELECTIVITY = 0.1;
    static constexpr double EQUALITY_PREDICATE_SELECTIVITY = 0.01;
    static constexpr uint64_t BUILD_PENALTY = 2;
    // Avoid doing probe to build SIP if we have to accumulate a probe side that is much bigger than
    // build side. Also avoid doing build to probe SIP if probe side is not much bigger than build.
    static constexpr uint64_t SIP_RATIO = 5;
};

struct OrderByConstants {
    static constexpr uint64_t NUM_BYTES_FOR_PAYLOAD_IDX = 8;
    static constexpr uint64_t MIN_LIMIT_RATIO_TO_REDUCE = 2;
};

struct ParquetConstants {
    static constexpr uint64_t PARQUET_DEFINE_VALID = 65535;
    static constexpr const char* PARQUET_MAGIC_WORDS = "PAR1";
    // We limit the uncompressed page size to 100MB.
    // The max size in Parquet is 2GB, but we choose a more conservative limit.
    static constexpr uint64_t MAX_UNCOMPRESSED_PAGE_SIZE = 100000000;
    // Dictionary pages must be below 2GB. Unlike data pages, there's only one dictionary page.
    // For this reason we go with a much higher, but still a conservative upper bound of 1GB.
    static constexpr uint64_t MAX_UNCOMPRESSED_DICT_PAGE_SIZE = 1e9;
    // The maximum size a key entry in an RLE page takes.
    static constexpr uint64_t MAX_DICTIONARY_KEY_SIZE = sizeof(uint32_t);
    // The size of encoding the string length.
    static constexpr uint64_t STRING_LENGTH_SIZE = sizeof(uint32_t);
    static constexpr uint64_t MAX_STRING_STATISTICS_SIZE = 10000;
    static constexpr uint64_t PARQUET_INTERVAL_SIZE = 12;
    static constexpr uint64_t PARQUET_UUID_SIZE = 16;
};

struct ExportCSVConstants {
    static constexpr const char* DEFAULT_CSV_NEWLINE = "\n\r";
    static constexpr const char* DEFAULT_NULL_STR = "";
    static constexpr bool DEFAULT_FORCE_QUOTE = false;
    static constexpr uint64_t DEFAULT_CSV_FLUSH_SIZE = 4096 * 8;
};

struct PortDBConstants {
    static constexpr char INDEX_FILE_NAME[] = "index.cypher";
    static constexpr char SCHEMA_FILE_NAME[] = "schema.cypher";
    static constexpr char COPY_FILE_NAME[] = "copy.cypher";
    static constexpr const char* SCHEMA_ONLY_OPTION = "SCHEMA_ONLY";
    static constexpr const char* EXPORT_FORMAT_OPTION = "FORMAT";
    static constexpr const char* DEFAULT_EXPORT_FORMAT_OPTION = "PARQUET";
};

struct WarningConstants {
    static constexpr std::array WARNING_TABLE_COLUMN_NAMES{"query_id", "message", "file_path",
        "line_number", "skipped_line_or_record"};
    static constexpr std::array WARNING_TABLE_COLUMN_DATA_TYPES{LogicalTypeID::UINT64,
        LogicalTypeID::STRING, LogicalTypeID::STRING, LogicalTypeID::UINT64, LogicalTypeID::STRING};
    static constexpr uint64_t WARNING_TABLE_NUM_COLUMNS = WARNING_TABLE_COLUMN_NAMES.size();

    static_assert(WARNING_TABLE_COLUMN_DATA_TYPES.size() == WARNING_TABLE_NUM_COLUMNS);
};

static constexpr char ATTACHED_LBUG_DB_TYPE[] = "LBUG";

static constexpr char LOCAL_DB_NAME[] = "local(lbug)";

constexpr auto DECIMAL_PRECISION_LIMIT = 38;

} // namespace common
} // namespace lbug
