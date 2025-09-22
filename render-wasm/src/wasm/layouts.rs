use crate::shapes::Sizing;
use crate::{with_current_shape_mut, STATE};
use macros::ToJs;

mod align;
mod constraints;
mod flex;
mod grid;

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawSizing {
    Fill = 0,
    Fix = 1,
    Auto = 2,
}

impl From<u8> for RawSizing {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawSizing> for Sizing {
    fn from(value: RawSizing) -> Self {
        match value {
            RawSizing::Fill => Sizing::Fill,
            RawSizing::Fix => Sizing::Fix,
            RawSizing::Auto => Sizing::Auto,
        }
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_layout();
    });
}

#[no_mangle]
pub extern "C" fn set_layout_child_data(
    margin_top: f32,
    margin_right: f32,
    margin_bottom: f32,
    margin_left: f32,
    h_sizing: u8,
    v_sizing: u8,
    has_max_h: bool,
    max_h: f32,
    has_min_h: bool,
    min_h: f32,
    has_max_w: bool,
    max_w: f32,
    has_min_w: bool,
    min_w: f32,
    align_self: u8,
    is_absolute: bool,
    z_index: i32,
) {
    let h_sizing = RawSizing::from(h_sizing);
    let v_sizing = RawSizing::from(v_sizing);
    let max_h = if has_max_h { Some(max_h) } else { None };
    let min_h = if has_min_h { Some(min_h) } else { None };
    let max_w = if has_max_w { Some(max_w) } else { None };
    let min_w = if has_min_w { Some(min_w) } else { None };

    let raw_align_self = align::RawAlignSelf::from(align_self);

    let align_self = raw_align_self.try_into().ok();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_child_data(
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing.into(),
            v_sizing.into(),
            max_h,
            min_h,
            max_w,
            min_w,
            align_self,
            is_absolute,
            z_index,
        );
    });
}
