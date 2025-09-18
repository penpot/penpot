use crate::shapes::{self};
use crate::{with_current_shape_mut, STATE};

mod align;
mod constraints;
mod flex;
mod grid;

pub use align::RawJustifySelf; // FIXME: fix this

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
    has_align_self: bool,
    align_self: u8,
    is_absolute: bool,
    z_index: i32,
) {
    let h_sizing = shapes::Sizing::from_u8(h_sizing);
    let v_sizing = shapes::Sizing::from_u8(v_sizing);
    let max_h = if has_max_h { Some(max_h) } else { None };
    let min_h = if has_min_h { Some(min_h) } else { None };
    let max_w = if has_max_w { Some(max_w) } else { None };
    let min_w = if has_min_w { Some(min_w) } else { None };
    let align_self = if has_align_self {
        shapes::AlignSelf::from_u8(align_self)
    } else {
        None
    };

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_child_data(
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing,
            v_sizing,
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
