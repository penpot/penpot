use macros::ToJs;

use crate::shapes::{ConstraintH, ConstraintV};
use crate::with_current_shape_mut;

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawConstraintH {
    None = 0,
    Left = 1,
    Right = 2,
    Leftright = 3, // odd casing to comply with cljs value
    Center = 4,
    Scale = 5,
}

impl From<u8> for RawConstraintH {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawConstraintH> for Option<ConstraintH> {
    fn from(value: RawConstraintH) -> Self {
        match value {
            RawConstraintH::None => None,
            RawConstraintH::Left => Some(ConstraintH::Left),
            RawConstraintH::Right => Some(ConstraintH::Right),
            RawConstraintH::Leftright => Some(ConstraintH::LeftRight),
            RawConstraintH::Center => Some(ConstraintH::Center),
            RawConstraintH::Scale => Some(ConstraintH::Scale),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawConstraintV {
    None = 0,
    Top = 1,
    Bottom = 2,
    Topbottom = 3, // odd casing to comply with cljs value
    Center = 4,
    Scale = 5,
}

impl From<u8> for RawConstraintV {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawConstraintV> for Option<ConstraintV> {
    fn from(value: RawConstraintV) -> Self {
        match value {
            RawConstraintV::None => None,
            RawConstraintV::Top => Some(ConstraintV::Top),
            RawConstraintV::Bottom => Some(ConstraintV::Bottom),
            RawConstraintV::Topbottom => Some(ConstraintV::TopBottom),
            RawConstraintV::Center => Some(ConstraintV::Center),
            RawConstraintV::Scale => Some(ConstraintV::Scale),
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_h(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let constraint: Option<ConstraintH> = RawConstraintH::from(constraint).into();
        shape.set_constraint_h(constraint);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_v(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let constraint: Option<ConstraintV> = RawConstraintV::from(constraint).into();
        shape.set_constraint_v(constraint);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_constraints() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_constraints();
    });
}
