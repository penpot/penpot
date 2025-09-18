use macros::ToJs;

use crate::shapes::VerticalAlign;
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawVerticalAlign {
    Top = 0,
    Center = 1,
    Bottom = 2,
}

impl From<u8> for RawVerticalAlign {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawVerticalAlign> for VerticalAlign {
    fn from(value: RawVerticalAlign) -> Self {
        match value {
            RawVerticalAlign::Top => VerticalAlign::Top,
            RawVerticalAlign::Center => VerticalAlign::Center,
            RawVerticalAlign::Bottom => VerticalAlign::Bottom,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_vertical_align(align: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let align = RawVerticalAlign::from(align);
        shape.set_vertical_align(align.into());
    });
}
