use crate::mem;
use crate::shapes;
use crate::with_current_shape_mut;
use crate::STATE;

#[no_mangle]
pub extern "C" fn add_shape_center_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_center_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_inner_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_inner_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_outer_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_outer_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_fill() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let raw_fill = super::fills::RawFillData::try_from(&bytes[..]).expect("Invalid fill data");
        shape
            .set_stroke_fill(raw_fill.into())
            .expect("could not add stroke fill");
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_strokes() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_strokes();
    });
}
