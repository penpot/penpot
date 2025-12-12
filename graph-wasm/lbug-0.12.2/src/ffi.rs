#[cfg(feature = "arrow")]
pub(crate) mod arrow;

use cxx::{type_id, ExternType};
use std::marker::PhantomData;
use std::mem::MaybeUninit;
use std::os::raw::{c_char, c_void};

// See https://github.com/dtolnay/cxx/issues/734
#[derive(Copy, Clone)]
#[repr(C)]
pub struct StringView<'a> {
    repr: MaybeUninit<[*const c_void; 2]>,
    borrow: PhantomData<&'a [c_char]>,
}

unsafe impl ExternType for StringView<'_> {
    type Id = type_id!("std::string_view");
    type Kind = cxx::kind::Trivial;
}

impl<'a> StringView<'a> {
    pub fn new(s: &'a str) -> Self {
        ffi::string_view_from_str(s)
    }
}

#[allow(clippy::module_inception)]
#[allow(clippy::needless_lifetimes)]
#[cxx::bridge]
pub(crate) mod ffi {
    unsafe extern "C++" {
        include!("lbug/include/lbug_rs.h");
        #[namespace = "std"]
        #[cxx_name = "string_view"]
        type StringView<'a> = crate::ffi::StringView<'a>;

        #[namespace = "lbug_rs"]
        fn string_view_from_str(s: &str) -> StringView<'_>;
    }

    // From types.h
    // Note: cxx will check if values change, but not if they are added.
    #[namespace = "lbug::common"]
    #[repr(u8)]
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum LogicalTypeID {
        ANY = 0,
        NODE = 10,
        REL = 11,
        RECURSIVE_REL = 12,
        // SERIAL is a special data type that is used to represent a sequence of INT64 values that are
        // incremented by 1 starting from 0.
        SERIAL = 13,

        // fixed size types
        BOOL = 22,
        INT64 = 23,
        INT32 = 24,
        INT16 = 25,
        INT8 = 26,
        UINT64 = 27,
        UINT32 = 28,
        UINT16 = 29,
        UINT8 = 30,
        INT128 = 31,
        DOUBLE = 32,
        FLOAT = 33,
        DATE = 34,
        TIMESTAMP = 35,
        TIMESTAMP_SEC = 36,
        TIMESTAMP_MS = 37,
        TIMESTAMP_NS = 38,
        TIMESTAMP_TZ = 39,
        INTERVAL = 40,
        DECIMAL = 41,
        INTERNAL_ID = 42,

        // variable size types
        STRING = 50,
        BLOB = 51,
        LIST = 52,
        ARRAY = 53,
        STRUCT = 54,
        MAP = 55,
        UNION = 56,

        UUID = 59,
    }

    // From types.h
    // Note: cxx will check if values change, but not if they are added.
    #[namespace = "lbug::common"]
    #[repr(u8)]
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum PhysicalTypeID {
        // Fixed size types.
        ANY = 0,
        BOOL = 1,
        INT64 = 2,
        INT32 = 3,
        INT16 = 4,
        INT8 = 5,
        UINT64 = 6,
        UINT32 = 7,
        UINT16 = 8,
        UINT8 = 9,
        INT128 = 10,
        DOUBLE = 11,
        FLOAT = 12,
        INTERVAL = 13,
        INTERNAL_ID = 14,

        // Variable size types.
        STRING = 20,
        LIST = 22,
        ARRAY = 23,
        STRUCT = 24,
        POINTER = 25,
    }

    #[namespace = "lbug::common"]
    unsafe extern "C++" {
        type LogicalTypeID;
        type PhysicalTypeID;
    }

    #[namespace = "lbug::main"]
    unsafe extern "C++" {
        type PreparedStatement;
        fn isSuccess(&self) -> bool;

        #[namespace = "lbug_rs"]
        fn prepared_statement_error_message(statement: &PreparedStatement) -> String;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        type QueryParams;

        // Simple types which cross the ffi without problems
        // Non-copyable types are references so that they only need to be cloned on the
        // C++ side of things
        fn insert(self: Pin<&mut Self>, key: &str, value: UniquePtr<Value>);

        fn new_params() -> UniquePtr<QueryParams>;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        #[namespace = "lbug::main"]
        type Database;

        #[allow(clippy::fn_params_excessive_bools)]
        fn new_database(
            databasePath: StringView,
            bufferPoolSize: u64,
            maxNumThreads: u64,
            enableCompression: bool,
            readOnly: bool,
            maxDBSize: u64,
            auto_checkpoint: bool,
            checkpoint_threshold: i64,
            throw_on_wal_replay_failure: bool,
            enable_checksums: bool,
        ) -> Result<UniquePtr<Database>>;

    }

    #[namespace = "lbug::main"]
    unsafe extern "C++" {
        // The C++ Connection class includes a pointer to the database.
        // We must not destroy a referenced database while a connection is open.
        type Connection<'db>;

        #[namespace = "lbug_rs"]
        fn database_connect(database: Pin<&mut Database>) -> Result<UniquePtr<Connection<'_>>>;

        fn prepare(
            self: Pin<&mut Connection>,
            query: StringView<'_>,
        ) -> Result<UniquePtr<PreparedStatement>>;

        #[namespace = "lbug_rs"]
        fn connection_execute<'db>(
            connection: Pin<&mut Connection<'db>>,
            query: Pin<&mut PreparedStatement>,
            params: UniquePtr<QueryParams>,
        ) -> Result<UniquePtr<QueryResult<'db>>>;

        #[namespace = "lbug_rs"]
        fn connection_query<'a, 'db>(
            connection: Pin<&mut Connection<'db>>,
            query: StringView<'a>,
        ) -> Result<UniquePtr<QueryResult<'db>>>;

        fn getMaxNumThreadForExec(self: Pin<&mut Connection>) -> u64;
        fn setMaxNumThreadForExec(self: Pin<&mut Connection>, num_threads: u64);
        fn interrupt(self: Pin<&mut Connection>) -> Result<()>;
        fn setQueryTimeOut(self: Pin<&mut Connection>, timeout_ms: u64);
    }

    #[namespace = "lbug::main"]
    unsafe extern "C++" {
        // The C++ QueryResult class includes a pointer to part of the Database
        // (at minimum, the FactorizedTable references the MemoryManager)
        type QueryResult<'db>;

        #[namespace = "lbug_rs"]
        fn query_result_to_string(query_result: &QueryResult) -> String;
        fn isSuccess(&self) -> bool;
        #[namespace = "lbug_rs"]
        fn query_result_get_error_message(query_result: &QueryResult) -> String;
        fn hasNext(&self) -> bool;
        fn getNext(self: Pin<&mut QueryResult>) -> SharedPtr<FlatTuple>;

        #[namespace = "lbug_rs"]
        fn query_result_get_compiling_time(result: &QueryResult) -> f64;
        #[namespace = "lbug_rs"]
        fn query_result_get_execution_time(result: &QueryResult) -> f64;
        fn getNumColumns(&self) -> usize;
        fn getNumTuples(&self) -> u64;

        #[namespace = "lbug_rs"]
        fn query_result_column_data_types(
            query_result: &QueryResult,
        ) -> UniquePtr<CxxVector<LogicalType>>;
        #[namespace = "lbug_rs"]
        fn query_result_column_names(query_result: &QueryResult) -> Vec<String>;
    }

    #[namespace = "lbug::processor"]
    unsafe extern "C++" {
        type FlatTuple;

        fn len(&self) -> u32;
        #[namespace = "lbug_rs"]
        fn flat_tuple_get_value(tuple: &FlatTuple, index: u32) -> &Value;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        #[namespace = "lbug::common"]
        type LogicalType;

        #[namespace = "lbug::common"]
        fn getLogicalTypeID(&self) -> LogicalTypeID;

        fn create_logical_type(id: LogicalTypeID) -> UniquePtr<LogicalType>;
        fn create_logical_type_list(child_type: UniquePtr<LogicalType>) -> UniquePtr<LogicalType>;
        fn create_logical_type_decimal(precision: u32, scale: u32) -> UniquePtr<LogicalType>;
        fn create_logical_type_array(
            child_type: UniquePtr<LogicalType>,
            num_elements: u64,
        ) -> UniquePtr<LogicalType>;
        fn create_logical_type_struct(
            field_names: &Vec<String>,
            types: UniquePtr<TypeListBuilder>,
        ) -> UniquePtr<LogicalType>;
        fn create_logical_type_union(
            field_names: &Vec<String>,
            types: UniquePtr<TypeListBuilder>,
        ) -> UniquePtr<LogicalType>;
        fn create_logical_type_map(
            keyType: UniquePtr<LogicalType>,
            valueType: UniquePtr<LogicalType>,
        ) -> UniquePtr<LogicalType>;

        fn logical_type_get_list_child_type(value: &LogicalType) -> UniquePtr<LogicalType>;
        fn logical_type_get_array_child_type(value: &LogicalType) -> UniquePtr<LogicalType>;
        fn logical_type_get_array_num_elements(value: &LogicalType) -> u64;
        fn logical_type_get_struct_field_names(value: &LogicalType) -> Vec<String>;
        fn logical_type_get_struct_field_types(
            value: &LogicalType,
        ) -> UniquePtr<CxxVector<LogicalType>>;

        fn logical_type_get_decimal_precision(value: &LogicalType) -> u32;
        fn logical_type_get_decimal_scale(value: &LogicalType) -> u32;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        type ValueListBuilder;

        fn insert(self: Pin<&mut ValueListBuilder>, value: UniquePtr<Value>);
        fn get_list_value(
            typ: UniquePtr<LogicalType>,
            value: UniquePtr<ValueListBuilder>,
        ) -> UniquePtr<Value>;
        fn create_list() -> UniquePtr<ValueListBuilder>;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        type TypeListBuilder;

        fn insert(self: Pin<&mut TypeListBuilder>, typ: UniquePtr<LogicalType>);
        fn create_type_list() -> UniquePtr<TypeListBuilder>;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        #[namespace = "lbug::common"]
        type Value;

        // only used by tests
        #[allow(dead_code)]
        fn value_to_string(node_value: &Value) -> String;

        #[rust_name = "get_value_bool"]
        fn getValue(&self) -> bool;
        #[rust_name = "get_value_i8"]
        fn getValue(&self) -> i8;
        #[rust_name = "get_value_i16"]
        fn getValue(&self) -> i16;
        #[rust_name = "get_value_i32"]
        fn getValue(&self) -> i32;
        #[rust_name = "get_value_i64"]
        fn getValue(&self) -> i64;
        #[rust_name = "get_value_u8"]
        fn getValue(&self) -> u8;
        #[rust_name = "get_value_u16"]
        fn getValue(&self) -> u16;
        #[rust_name = "get_value_u32"]
        fn getValue(&self) -> u32;
        #[rust_name = "get_value_u64"]
        fn getValue(&self) -> u64;
        #[rust_name = "get_value_float"]
        fn getValue(&self) -> f32;
        #[rust_name = "get_value_double"]
        fn getValue(&self) -> f64;

        fn value_get_string(value: &Value) -> &CxxString;
        fn value_get_interval_secs(value: &Value) -> i64;
        fn value_get_interval_micros(value: &Value) -> i32;
        fn value_get_timestamp_micros(value: &Value) -> i64;
        fn value_get_timestamp_ns(value: &Value) -> i64;
        fn value_get_timestamp_ms(value: &Value) -> i64;
        fn value_get_timestamp_sec(value: &Value) -> i64;
        fn value_get_timestamp_tz(value: &Value) -> i64;
        fn value_get_date_days(value: &Value) -> i32;
        fn value_get_int128_t(value: &Value) -> [u64; 2];
        fn value_get_internal_id(value: &Value) -> [u64; 2];

        fn value_get_data_type_id(value: &Value) -> LogicalTypeID;
        fn value_get_data_type(value: &Value) -> &LogicalType;
        fn value_get_physical_type(value: &Value) -> PhysicalTypeID;

        fn value_get_children_size(value: &Value) -> u32;
        fn value_get_child(value: &Value, index: u32) -> &Value;

        fn isNull(&self) -> bool;

        #[rust_name = "create_value_bool"]
        fn create_value(value: bool) -> UniquePtr<Value>;
        #[rust_name = "create_value_i8"]
        fn create_value(value: i8) -> UniquePtr<Value>;
        #[rust_name = "create_value_i16"]
        fn create_value(value: i16) -> UniquePtr<Value>;
        #[rust_name = "create_value_i32"]
        fn create_value(value: i32) -> UniquePtr<Value>;
        #[rust_name = "create_value_i64"]
        fn create_value(value: i64) -> UniquePtr<Value>;
        #[rust_name = "create_value_u8"]
        fn create_value(value: u8) -> UniquePtr<Value>;
        #[rust_name = "create_value_u16"]
        fn create_value(value: u16) -> UniquePtr<Value>;
        #[rust_name = "create_value_u32"]
        fn create_value(value: u32) -> UniquePtr<Value>;
        #[rust_name = "create_value_u64"]
        fn create_value(value: u64) -> UniquePtr<Value>;
        #[rust_name = "create_value_float"]
        fn create_value(value: f32) -> UniquePtr<Value>;
        #[rust_name = "create_value_double"]
        fn create_value(value: f64) -> UniquePtr<Value>;

        fn create_value_null(typ: UniquePtr<LogicalType>) -> UniquePtr<Value>;
        fn create_value_string(typ: LogicalTypeID, value: &[u8]) -> UniquePtr<Value>;
        fn create_value_timestamp(value: i64) -> UniquePtr<Value>;
        fn create_value_timestamp_tz(value: i64) -> UniquePtr<Value>;
        fn create_value_timestamp_ns(value: i64) -> UniquePtr<Value>;
        fn create_value_timestamp_ms(value: i64) -> UniquePtr<Value>;
        fn create_value_timestamp_sec(value: i64) -> UniquePtr<Value>;
        fn create_value_date(value: i32) -> UniquePtr<Value>;
        fn create_value_interval(months: i32, days: i32, micros: i64) -> UniquePtr<Value>;
        fn create_value_int128_t(high: i64, low: u64) -> UniquePtr<Value>;
        fn create_value_uuid_t(high: i64, low: u64) -> UniquePtr<Value>;
        fn create_value_internal_id(offset: u64, table: u64) -> UniquePtr<Value>;
        fn create_value_decimal(
            high: i64,
            low: u64,
            scale: u32,
            precision: u32,
        ) -> UniquePtr<Value>;

        fn node_value_get_node_id(value: &Value) -> &Value;
        fn node_value_get_label_name(value: &Value) -> String;

        fn node_value_get_num_properties(value: &Value) -> usize;
        fn node_value_get_property_name(value: &Value, index: usize) -> String;
        fn node_value_get_property_value(value: &Value, index: usize) -> &Value;

        fn rel_value_get_label_name(value: &Value) -> String;

        fn rel_value_get_src_id(value: &Value) -> &Value;
        fn rel_value_get_dst_id(value: &Value) -> [u64; 2];

        fn rel_value_get_num_properties(value: &Value) -> usize;
        fn rel_value_get_property_name(value: &Value, index: usize) -> String;
        fn rel_value_get_property_value(value: &Value, index: usize) -> &Value;

        fn recursive_rel_get_nodes(value: &Value) -> &Value;
        fn recursive_rel_get_rels(value: &Value) -> &Value;
    }

    #[namespace = "lbug_rs"]
    unsafe extern "C++" {
        fn get_storage_version() -> u64;
    }
}
