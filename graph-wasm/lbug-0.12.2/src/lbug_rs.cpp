#include "lbug_rs.h"

using lbug::common::ArrayTypeInfo;
using lbug::common::Interval;
using lbug::common::ListTypeInfo;
using lbug::common::LogicalType;
using lbug::common::LogicalTypeID;
using lbug::common::NodeVal;
using lbug::common::RelVal;
using lbug::common::StructField;
using lbug::common::Value;
using lbug::main::Connection;
using lbug::main::Database;
using lbug::main::SystemConfig;

namespace lbug_rs {

std::unique_ptr<QueryParams> new_params() {
    return std::make_unique<QueryParams>();
}

std::unique_ptr<LogicalType> create_logical_type(lbug::common::LogicalTypeID id) {
    return std::make_unique<LogicalType>(id);
}
std::unique_ptr<LogicalType> create_logical_type_list(std::unique_ptr<LogicalType> childType) {
    return std::make_unique<LogicalType>(LogicalType::LIST(std::move(*childType)));
}

std::unique_ptr<LogicalType> create_logical_type_array(std::unique_ptr<LogicalType> childType,
    uint64_t numElements) {
    return std::make_unique<LogicalType>(LogicalType::ARRAY(std::move(*childType), numElements));
}

std::unique_ptr<lbug::common::LogicalType> create_logical_type_map(
    std::unique_ptr<LogicalType> keyType, std::unique_ptr<LogicalType> valueType) {
    return std::make_unique<LogicalType>(
        LogicalType::MAP(std::move(*keyType), std::move(*valueType)));
}

std::unique_ptr<LogicalType> logical_type_get_list_child_type(const LogicalType& logicalType) {
    return std::make_unique<LogicalType>(lbug::common::ListType::getChildType(logicalType).copy());
}
std::unique_ptr<LogicalType> logical_type_get_array_child_type(const LogicalType& logicalType) {
    return std::make_unique<LogicalType>(lbug::common::ArrayType::getChildType(logicalType).copy());
}
uint64_t logical_type_get_array_num_elements(const LogicalType& logicalType) {
    return lbug::common::ArrayType::getNumElements(logicalType);
}

rust::Vec<rust::String> logical_type_get_struct_field_names(
    const lbug::common::LogicalType& value) {
    rust::Vec<rust::String> names;
    for (auto name : lbug::common::StructType::getFieldNames(value)) {
        names.push_back(name);
    }
    return names;
}

std::unique_ptr<std::vector<lbug::common::LogicalType>> logical_type_get_struct_field_types(
    const lbug::common::LogicalType& value) {
    std::vector<lbug::common::LogicalType> result;
    for (const auto& type : lbug::common::StructType::getFieldTypes(value)) {
        result.push_back(type->copy());
    }
    return std::make_unique<std::vector<LogicalType>>(std::move(result));
}

std::unique_ptr<Database> new_database(std::string_view databasePath, uint64_t bufferPoolSize,
    uint64_t maxNumThreads, bool enableCompression, bool readOnly, uint64_t maxDBSize,
    bool autoCheckpoint, int64_t checkpointThreshold, bool throwOnWalReplayFailure,
    bool enableChecksums) {
    auto systemConfig = SystemConfig();
    if (bufferPoolSize > 0) {
        systemConfig.bufferPoolSize = bufferPoolSize;
    }
    if (maxNumThreads > 0) {
        systemConfig.maxNumThreads = maxNumThreads;
    }
    systemConfig.readOnly = readOnly;
    systemConfig.enableCompression = enableCompression;
    if (maxDBSize != -1u) {
        systemConfig.maxDBSize = maxDBSize;
    }
    systemConfig.autoCheckpoint = autoCheckpoint;
    if (checkpointThreshold >= 0) {
        systemConfig.checkpointThreshold = checkpointThreshold;
    }
    systemConfig.throwOnWalReplayFailure = throwOnWalReplayFailure;
    systemConfig.enableChecksums = enableChecksums;
    return std::make_unique<Database>(databasePath, systemConfig);
}

std::unique_ptr<lbug::main::Connection> database_connect(lbug::main::Database& database) {
    return std::make_unique<Connection>(&database);
}

std::unique_ptr<lbug::main::QueryResult> connection_execute(lbug::main::Connection& connection,
    lbug::main::PreparedStatement& query, std::unique_ptr<QueryParams> params) {
    return connection.executeWithParams(&query, std::move(params->inputParams));
}

rust::String prepared_statement_error_message(const lbug::main::PreparedStatement& statement) {
    return rust::String(statement.getErrorMessage());
}

rust::String query_result_to_string(const lbug::main::QueryResult& result) {
    return rust::String(result.toString());
}

rust::String query_result_get_error_message(const lbug::main::QueryResult& result) {
    return rust::String(result.getErrorMessage());
}

double query_result_get_compiling_time(const lbug::main::QueryResult& result) {
    return result.getQuerySummary()->getCompilingTime();
}
double query_result_get_execution_time(const lbug::main::QueryResult& result) {
    return result.getQuerySummary()->getExecutionTime();
}

std::unique_ptr<std::vector<lbug::common::LogicalType>> query_result_column_data_types(
    const lbug::main::QueryResult& query_result) {
    return std::make_unique<std::vector<lbug::common::LogicalType>>(
        query_result.getColumnDataTypes());
}
rust::Vec<rust::String> query_result_column_names(const lbug::main::QueryResult& query_result) {
    rust::Vec<rust::String> names;
    for (auto name : query_result.getColumnNames()) {
        names.push_back(name);
    }
    return names;
}

rust::String node_value_get_label_name(const lbug::common::Value& val) {
    return rust::String(lbug::common::NodeVal::getLabelVal(&val)->getValue<std::string>());
}

rust::String rel_value_get_label_name(const lbug::common::Value& val) {
    return rust::String(lbug::common::RelVal::getLabelVal(&val)->getValue<std::string>());
}

size_t node_value_get_num_properties(const Value& value) {
    return NodeVal::getNumProperties(&value);
}
size_t rel_value_get_num_properties(const Value& value) {
    return RelVal::getNumProperties(&value);
}

rust::String node_value_get_property_name(const Value& value, size_t index) {
    return rust::String(NodeVal::getPropertyName(&value, index));
}
rust::String rel_value_get_property_name(const Value& value, size_t index) {
    return rust::String(RelVal::getPropertyName(&value, index));
}

const lbug::common::Value& node_value_get_property_value(const Value& value, size_t index) {
    return *NodeVal::getPropertyVal(&value, index);
}
const lbug::common::Value& rel_value_get_property_value(const Value& value, size_t index) {
    return *RelVal::getPropertyVal(&value, index);
}

const Value& node_value_get_node_id(const lbug::common::Value& val) {
    return *lbug::common::NodeVal::getNodeIDVal(&val);
}

const Value& rel_value_get_src_id(const lbug::common::Value& val) {
    return *lbug::common::RelVal::getSrcNodeIDVal(&val);
}
std::array<uint64_t, 2> rel_value_get_dst_id(const lbug::common::Value& val) {
    auto internalID =
        lbug::common::RelVal::getDstNodeIDVal(&val)->getValue<lbug::common::internalID_t>();
    return std::array{internalID.offset, internalID.tableID};
}

rust::String value_to_string(const lbug::common::Value& val) {
    return rust::String(val.toString());
}

uint32_t flat_tuple_len(const lbug::processor::FlatTuple& flatTuple) {
    return flatTuple.len();
}
const lbug::common::Value& flat_tuple_get_value(const lbug::processor::FlatTuple& flatTuple,
    uint32_t index) {
    return flatTuple[index];
}

const std::string& value_get_string(const lbug::common::Value& value) {
    return value.strVal;
}
int64_t value_get_interval_secs(const lbug::common::Value& value) {
    auto interval = value.getValue<lbug::common::interval_t>();
    return (interval.months * Interval::DAYS_PER_MONTH + interval.days) * Interval::HOURS_PER_DAY *
               Interval::MINS_PER_HOUR * Interval::SECS_PER_MINUTE
           // Include extra microseconds with the seconds
           + interval.micros / Interval::MICROS_PER_SEC;
}
int32_t value_get_interval_micros(const lbug::common::Value& value) {
    auto interval = value.getValue<lbug::common::interval_t>();
    return interval.micros % Interval::MICROS_PER_SEC;
}
int32_t value_get_date_days(const lbug::common::Value& value) {
    return value.getValue<lbug::common::date_t>().days;
}
int64_t value_get_timestamp_micros(const lbug::common::Value& value) {
    return value.getValue<lbug::common::timestamp_t>().value;
}
int64_t value_get_timestamp_ns(const lbug::common::Value& value) {
    return value.getValue<lbug::common::timestamp_ns_t>().value;
}
int64_t value_get_timestamp_ms(const lbug::common::Value& value) {
    return value.getValue<lbug::common::timestamp_ms_t>().value;
}
int64_t value_get_timestamp_sec(const lbug::common::Value& value) {
    return value.getValue<lbug::common::timestamp_sec_t>().value;
}
int64_t value_get_timestamp_tz(const lbug::common::Value& value) {
    return value.getValue<lbug::common::timestamp_tz_t>().value;
}
std::array<uint64_t, 2> value_get_int128_t(const lbug::common::Value& value) {
    auto int128 = value.getValue<lbug::common::int128_t>();
    auto int128_high = static_cast<uint64_t>(int128.high);
    return std::array{int128_high, int128.low};
}

std::array<uint64_t, 2> value_get_internal_id(const lbug::common::Value& value) {
    auto internalID = value.getValue<lbug::common::internalID_t>();
    return std::array{internalID.offset, internalID.tableID};
}

uint32_t value_get_children_size(const lbug::common::Value& value) {
    return lbug::common::NestedVal::getChildrenSize(&value);
}

const Value& value_get_child(const lbug::common::Value& value, uint32_t index) {
    return *lbug::common::NestedVal::getChildVal(&value, index);
}

lbug::common::LogicalTypeID value_get_data_type_id(const lbug::common::Value& value) {
    return value.getDataType().getLogicalTypeID();
}

const LogicalType& value_get_data_type(const lbug::common::Value& value) {
    return value.getDataType();
}

std::unique_ptr<lbug::common::Value> create_value_string(LogicalTypeID typ,
    const rust::Slice<const unsigned char> value) {
    return std::make_unique<lbug::common::Value>(LogicalType(typ),
        std::string((const char*)value.data(), value.size()));
}
std::unique_ptr<lbug::common::Value> create_value_timestamp(const int64_t timestamp) {
    return std::make_unique<lbug::common::Value>(lbug::common::timestamp_t(timestamp));
}
std::unique_ptr<lbug::common::Value> create_value_timestamp_tz(const int64_t timestamp) {
    return std::make_unique<lbug::common::Value>(lbug::common::timestamp_tz_t(timestamp));
}
std::unique_ptr<lbug::common::Value> create_value_timestamp_ns(const int64_t timestamp) {
    return std::make_unique<lbug::common::Value>(lbug::common::timestamp_ns_t(timestamp));
}
std::unique_ptr<lbug::common::Value> create_value_timestamp_ms(const int64_t timestamp) {
    return std::make_unique<lbug::common::Value>(lbug::common::timestamp_ms_t(timestamp));
}
std::unique_ptr<lbug::common::Value> create_value_timestamp_sec(const int64_t timestamp) {
    return std::make_unique<lbug::common::Value>(lbug::common::timestamp_sec_t(timestamp));
}
std::unique_ptr<lbug::common::Value> create_value_interval(const int32_t months, const int32_t days,
    const int64_t micros) {
    return std::make_unique<lbug::common::Value>(lbug::common::interval_t(months, days, micros));
}
std::unique_ptr<lbug::common::Value> create_value_null(
    std::unique_ptr<lbug::common::LogicalType> typ) {
    return std::make_unique<lbug::common::Value>(
        lbug::common::Value::createNullValue(std::move(*typ)));
}
std::unique_ptr<lbug::common::Value> create_value_internal_id(uint64_t offset, uint64_t table) {
    return std::make_unique<lbug::common::Value>(lbug::common::internalID_t(offset, table));
}
std::unique_ptr<lbug::common::Value> create_value_int128_t(int64_t high, uint64_t low) {
    return std::make_unique<lbug::common::Value>(lbug::common::int128_t(low, high));
}

std::unique_ptr<lbug::common::Value> get_list_value(std::unique_ptr<lbug::common::LogicalType> typ,
    std::unique_ptr<ValueListBuilder> value) {
    return std::make_unique<lbug::common::Value>(std::move(*typ), std::move(value->values));
}

std::unique_ptr<ValueListBuilder> create_list() {
    return std::make_unique<ValueListBuilder>();
}

std::unique_ptr<TypeListBuilder> create_type_list() {
    return std::make_unique<TypeListBuilder>();
}

const lbug::common::Value& recursive_rel_get_nodes(const lbug::common::Value& val) {
    return *lbug::common::RecursiveRelVal::getNodes(&val);
}
const lbug::common::Value& recursive_rel_get_rels(const lbug::common::Value& val) {
    return *lbug::common::RecursiveRelVal::getRels(&val);
}

} // namespace lbug_rs
