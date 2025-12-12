use crate::ffi::ffi;

/// Type of [Value](crate::value::Value)s produced and consumed by queries.
///
/// Includes extra type information beyond what can be encoded in [Value](crate::value::Value) such as
/// struct fields and types of lists
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LogicalType {
    /// Special type for use with [`Value::Null`](crate::value::Value::Null)
    Any,
    /// Correponds to [`Value::Bool`](crate::value::Value::Bool)
    Bool,
    /// Has no corresponding Value. Lbug returns Serial values as [`Int64`](crate::Value::Int64).
    Serial,
    /// Correponds to [`Value::Int64`](crate::value::Value::Int64)
    Int64,
    /// Correponds to [`Value::Int32`](crate::value::Value::Int32)
    Int32,
    /// Correponds to [`Value::Int16`](crate::value::Value::Int16)
    Int16,
    /// Correponds to [`Value::Int8`](crate::value::Value::Int8)
    Int8,
    /// Correponds to [`Value::UInt64`](crate::value::Value::UInt64)
    UInt64,
    /// Correponds to [`Value::UInt32`](crate::value::Value::UInt32)
    UInt32,
    /// Correponds to [`Value::UInt16`](crate::value::Value::UInt16)
    UInt16,
    /// Correponds to [`Value::UInt8`](crate::value::Value::UInt8)
    UInt8,
    /// Correponds to [`Value::Int128`](crate::value::Value::Int128)
    Int128,
    /// Correponds to [`Value::Double`](crate::value::Value::Double)
    Double,
    /// Correponds to [`Value::Float`](crate::value::Value::Float)
    Float,
    /// Correponds to [`Value::Date`](crate::value::Value::Date)
    Date,
    /// Correponds to [`Value::Interval`](crate::value::Value::Interval)
    Interval,
    /// Correponds to [`Value::Timestamp`](crate::value::Value::Timestamp)
    Timestamp,
    /// Correponds to [`Value::TimestampTz`](crate::value::Value::TimestampTz)
    TimestampTz,
    /// Correponds to [`Value::TimestampNs`](crate::value::Value::TimestampNs)
    TimestampNs,
    /// Correponds to [`Value::TimestampMs`](crate::value::Value::TimestampMs)
    TimestampMs,
    /// Correponds to [`Value::TimestampSec`](crate::value::Value::TimestampSec)
    TimestampSec,
    /// Correponds to [`Value::InternalID`](crate::value::Value::InternalID)
    InternalID,
    /// Correponds to [`Value::String`](crate::value::Value::String)
    String,
    /// Correponds to [`Value::Blob`](crate::value::Value::Blob)
    Blob,
    /// Correponds to [`Value::List`](crate::value::Value::List)
    List {
        child_type: Box<LogicalType>,
    },
    /// Correponds to [`Value::Array`](crate::value::Value::Array)
    Array {
        child_type: Box<LogicalType>,
        num_elements: u64,
    },
    /// Correponds to [`Value::Struct`](crate::value::Value::Struct)
    Struct {
        fields: Vec<(String, LogicalType)>,
    },
    /// Correponds to [`Value::Node`](crate::value::Value::Node)
    Node,
    /// Correponds to [`Value::Rel`](crate::value::Value::Rel)
    Rel,
    RecursiveRel,
    /// Correponds to [`Value::Map`](crate::value::Value::Map)
    Map {
        key_type: Box<LogicalType>,
        value_type: Box<LogicalType>,
    },
    /// Correponds to [`Value::Union`](crate::value::Value::Union)
    Union {
        types: Vec<(String, LogicalType)>,
    },
    /// Correponds to [`Value::UUID`](crate::value::Value::UUID)
    UUID,
    /// Correponds to [`Value::Decimal`](crate::value::Value::Decimal)
    Decimal {
        precision: u32,
        scale: u32,
    },
}

impl From<&ffi::Value> for LogicalType {
    fn from(value: &ffi::Value) -> Self {
        ffi::value_get_data_type(value).into()
    }
}

impl From<&ffi::LogicalType> for LogicalType {
    fn from(logical_type: &ffi::LogicalType) -> Self {
        use ffi::LogicalTypeID;
        match logical_type.getLogicalTypeID() {
            LogicalTypeID::ANY => LogicalType::Any,
            LogicalTypeID::BOOL => LogicalType::Bool,
            LogicalTypeID::SERIAL => LogicalType::Serial,
            LogicalTypeID::INT8 => LogicalType::Int8,
            LogicalTypeID::INT16 => LogicalType::Int16,
            LogicalTypeID::INT32 => LogicalType::Int32,
            LogicalTypeID::INT64 => LogicalType::Int64,
            LogicalTypeID::UINT8 => LogicalType::UInt8,
            LogicalTypeID::UINT16 => LogicalType::UInt16,
            LogicalTypeID::UINT32 => LogicalType::UInt32,
            LogicalTypeID::UINT64 => LogicalType::UInt64,
            LogicalTypeID::INT128 => LogicalType::Int128,
            LogicalTypeID::FLOAT => LogicalType::Float,
            LogicalTypeID::DOUBLE => LogicalType::Double,
            LogicalTypeID::STRING => LogicalType::String,
            LogicalTypeID::BLOB => LogicalType::Blob,
            LogicalTypeID::INTERVAL => LogicalType::Interval,
            LogicalTypeID::DATE => LogicalType::Date,
            LogicalTypeID::TIMESTAMP => LogicalType::Timestamp,
            LogicalTypeID::TIMESTAMP_TZ => LogicalType::TimestampTz,
            LogicalTypeID::TIMESTAMP_NS => LogicalType::TimestampNs,
            LogicalTypeID::TIMESTAMP_MS => LogicalType::TimestampMs,
            LogicalTypeID::TIMESTAMP_SEC => LogicalType::TimestampSec,
            LogicalTypeID::INTERNAL_ID => LogicalType::InternalID,
            LogicalTypeID::LIST => LogicalType::List {
                child_type: Box::new(
                    ffi::logical_type_get_list_child_type(logical_type)
                        .as_ref()
                        .unwrap()
                        .into(),
                ),
            },
            LogicalTypeID::ARRAY => LogicalType::Array {
                child_type: Box::new(
                    ffi::logical_type_get_array_child_type(logical_type)
                        .as_ref()
                        .unwrap()
                        .into(),
                ),
                num_elements: ffi::logical_type_get_array_num_elements(logical_type),
            },
            LogicalTypeID::STRUCT => {
                let names = ffi::logical_type_get_struct_field_names(logical_type);
                let types = ffi::logical_type_get_struct_field_types(logical_type);
                LogicalType::Struct {
                    fields: names
                        .into_iter()
                        .zip(types.into_iter().map(Into::<LogicalType>::into))
                        .collect(),
                }
            }
            LogicalTypeID::NODE => LogicalType::Node,
            LogicalTypeID::REL => LogicalType::Rel,
            LogicalTypeID::RECURSIVE_REL => LogicalType::RecursiveRel,
            LogicalTypeID::MAP => {
                let child_type_ptr = ffi::logical_type_get_list_child_type(logical_type);
                let child_type = child_type_ptr.as_ref().unwrap();
                let types = ffi::logical_type_get_struct_field_types(child_type);
                let key_type = types
                    .as_ref()
                    .unwrap()
                    .get(0)
                    .expect(
                        "First element of map type list should be the key type, but list was empty",
                    )
                    .into();
                let value_type = types.as_ref().unwrap()
                    .get(1)
                    .expect("Second element of map type list should be the value type, but list did not have two elements")
                    .into();
                LogicalType::Map {
                    key_type: Box::new(key_type),
                    value_type: Box::new(value_type),
                }
            }
            LogicalTypeID::UNION => {
                let names = ffi::logical_type_get_struct_field_names(logical_type);
                let types = ffi::logical_type_get_struct_field_types(logical_type);
                LogicalType::Union {
                    types: names
                        .into_iter()
                        // Skip the tag field
                        .skip(1)
                        .zip(types.into_iter().skip(1).map(Into::<LogicalType>::into))
                        .collect(),
                }
            }
            LogicalTypeID::UUID => LogicalType::UUID,
            LogicalTypeID::DECIMAL => {
                let precision = ffi::logical_type_get_decimal_precision(logical_type);
                let scale = ffi::logical_type_get_decimal_scale(logical_type);
                LogicalType::Decimal { precision, scale }
            }
            // Should be unreachable, as cxx will check that the LogicalTypeID enum matches the one
            // on the C++ side.
            x => panic!("Unsupported type {x:?}"),
        }
    }
}

impl From<&LogicalType> for cxx::UniquePtr<ffi::LogicalType> {
    fn from(typ: &LogicalType) -> Self {
        match typ {
            LogicalType::Any
            | LogicalType::Bool
            | LogicalType::Serial
            | LogicalType::Int64
            | LogicalType::Int32
            | LogicalType::Int16
            | LogicalType::Int8
            | LogicalType::UInt64
            | LogicalType::UInt32
            | LogicalType::UInt16
            | LogicalType::UInt8
            | LogicalType::Int128
            | LogicalType::Float
            | LogicalType::Double
            | LogicalType::Date
            | LogicalType::Timestamp
            | LogicalType::TimestampTz
            | LogicalType::TimestampNs
            | LogicalType::TimestampMs
            | LogicalType::TimestampSec
            | LogicalType::Interval
            | LogicalType::InternalID
            | LogicalType::String
            | LogicalType::Blob
            | LogicalType::Node
            | LogicalType::Rel
            | LogicalType::RecursiveRel
            | LogicalType::UUID => ffi::create_logical_type(typ.id()),
            LogicalType::List { child_type } => {
                ffi::create_logical_type_list(child_type.as_ref().into())
            }
            LogicalType::Array {
                child_type,
                num_elements,
            } => ffi::create_logical_type_array(child_type.as_ref().into(), *num_elements),
            LogicalType::Struct { fields } => {
                let mut builder = ffi::create_type_list();
                let mut names = vec![];
                for (name, typ) in fields {
                    names.push(name.clone());
                    builder.pin_mut().insert(typ.into());
                }
                ffi::create_logical_type_struct(&names, builder)
            }
            LogicalType::Union { types } => {
                let mut builder = ffi::create_type_list();
                let mut names = vec![];
                for (name, typ) in types {
                    names.push(name.clone());
                    builder.pin_mut().insert(typ.into());
                }
                ffi::create_logical_type_union(&names, builder)
            }
            LogicalType::Map {
                key_type,
                value_type,
            } => ffi::create_logical_type_map(key_type.as_ref().into(), value_type.as_ref().into()),
            LogicalType::Decimal { precision, scale } => {
                ffi::create_logical_type_decimal(*precision, *scale)
            }
        }
    }
}

impl LogicalType {
    pub(crate) fn id(&self) -> ffi::LogicalTypeID {
        use ffi::LogicalTypeID;
        match self {
            LogicalType::Any => LogicalTypeID::ANY,
            LogicalType::Bool => LogicalTypeID::BOOL,
            LogicalType::Serial => LogicalTypeID::SERIAL,
            LogicalType::Int8 => LogicalTypeID::INT8,
            LogicalType::Int16 => LogicalTypeID::INT16,
            LogicalType::Int32 => LogicalTypeID::INT32,
            LogicalType::Int64 => LogicalTypeID::INT64,
            LogicalType::UInt8 => LogicalTypeID::UINT8,
            LogicalType::UInt16 => LogicalTypeID::UINT16,
            LogicalType::UInt32 => LogicalTypeID::UINT32,
            LogicalType::UInt64 => LogicalTypeID::UINT64,
            LogicalType::Int128 => LogicalTypeID::INT128,
            LogicalType::Float => LogicalTypeID::FLOAT,
            LogicalType::Double => LogicalTypeID::DOUBLE,
            LogicalType::String => LogicalTypeID::STRING,
            LogicalType::Blob => LogicalTypeID::BLOB,
            LogicalType::Interval => LogicalTypeID::INTERVAL,
            LogicalType::Date => LogicalTypeID::DATE,
            LogicalType::Timestamp => LogicalTypeID::TIMESTAMP,
            LogicalType::TimestampTz => LogicalTypeID::TIMESTAMP_TZ,
            LogicalType::TimestampNs => LogicalTypeID::TIMESTAMP_NS,
            LogicalType::TimestampMs => LogicalTypeID::TIMESTAMP_MS,
            LogicalType::TimestampSec => LogicalTypeID::TIMESTAMP_SEC,
            LogicalType::InternalID => LogicalTypeID::INTERNAL_ID,
            LogicalType::List { .. } => LogicalTypeID::LIST,
            LogicalType::Array { .. } => LogicalTypeID::ARRAY,
            LogicalType::Struct { .. } => LogicalTypeID::STRUCT,
            LogicalType::Node => LogicalTypeID::NODE,
            LogicalType::Rel => LogicalTypeID::REL,
            LogicalType::RecursiveRel => LogicalTypeID::RECURSIVE_REL,
            LogicalType::Map { .. } => LogicalTypeID::MAP,
            LogicalType::Union { .. } => LogicalTypeID::UNION,
            LogicalType::UUID => LogicalTypeID::UUID,
            LogicalType::Decimal { .. } => LogicalTypeID::DECIMAL,
        }
    }
}
