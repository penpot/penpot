use macros::ToJs;

use indexmap::IndexSet;

use super::RawSegmentData;
use crate::math;
use crate::shapes::BoolType;
use crate::uuid::Uuid;
use crate::{mem, SerializableResult};
use crate::{with_current_shape_mut, with_state, STATE};

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawBoolType {
    Union = 0,
    Difference = 1,
    Intersection = 2,
    Exclusion = 3,
}

impl From<u8> for RawBoolType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawBoolType> for BoolType {
    fn from(value: RawBoolType) -> Self {
        match value {
            RawBoolType::Union => BoolType::Union,
            RawBoolType::Difference => BoolType::Difference,
            RawBoolType::Intersection => BoolType::Intersection,
            RawBoolType::Exclusion => BoolType::Exclusion,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_bool_type(raw_bool_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_bool_type(RawBoolType::from(raw_bool_type).into());
    });
}

#[no_mangle]
pub extern "C" fn calculate_bool(raw_bool_type: u8) -> *mut u8 {
    let bytes = mem::bytes_or_empty();

    let entries: IndexSet<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::from_bytes(data.try_into().unwrap()))
        .collect();

    mem::free_bytes();

    let bool_type = RawBoolType::from(raw_bool_type).into();
    let result;
    with_state!(state, {
        let path = math::bools::bool_from_shapes(
            bool_type,
            &entries,
            &state.shapes,
            &state.modifiers,
            &state.structure,
        );
        result = path
            .segments()
            .iter()
            .copied()
            .map(RawSegmentData::from_segment)
            .collect();
    });
    mem::write_vec(result)
}
