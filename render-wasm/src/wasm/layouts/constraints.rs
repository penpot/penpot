use macros::ToJs;

use crate::shapes::{ConstraintH, ConstraintV};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawConstraintH {
    Left = 0,
    Right = 1,
    Leftright = 2, // odd casing to comply with cljs value
    Center = 3,
    Scale = 4,
}

impl From<u8> for RawConstraintH {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawConstraintH> for ConstraintH {
    fn from(value: RawConstraintH) -> Self {
        match value {
            RawConstraintH::Left => ConstraintH::Left,
            RawConstraintH::Right => ConstraintH::Right,
            RawConstraintH::Leftright => ConstraintH::LeftRight,
            RawConstraintH::Center => ConstraintH::Center,
            RawConstraintH::Scale => ConstraintH::Scale,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawConstraintV {
    Top = 0,
    Bottom = 1,
    Topbottom = 2, // odd casing to comply with cljs value
    Center = 3,
    Scale = 4,
}

impl From<u8> for RawConstraintV {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawConstraintV> for ConstraintV {
    fn from(value: RawConstraintV) -> Self {
        match value {
            RawConstraintV::Top => ConstraintV::Top,
            RawConstraintV::Bottom => ConstraintV::Bottom,
            RawConstraintV::Topbottom => ConstraintV::TopBottom,
            RawConstraintV::Center => ConstraintV::Center,
            RawConstraintV::Scale => ConstraintV::Scale,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_h(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let constraint = RawConstraintH::from(constraint);
        shape.set_constraint_h(Some(constraint.into()));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_v(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let constraint = RawConstraintV::from(constraint);
        shape.set_constraint_v(Some(constraint.into()));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_constraints() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_constraints();
    });
}
