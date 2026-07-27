use macros::ToJs;

use crate::mem;
use crate::shapes::{self, StrokeCap, StrokeStyle};
use crate::with_current_shape_mut;

#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawStrokeStyle {
    Solid = 0,
    Dotted = 1,
    Dashed = 2,
    Mixed = 3,
}

impl From<u8> for RawStrokeStyle {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawStrokeStyle> for StrokeStyle {
    fn from(value: RawStrokeStyle) -> Self {
        match value {
            RawStrokeStyle::Solid => StrokeStyle::Solid,
            RawStrokeStyle::Dotted => StrokeStyle::Dotted,
            RawStrokeStyle::Dashed => StrokeStyle::Dashed,
            RawStrokeStyle::Mixed => StrokeStyle::Mixed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawStrokeCap {
    None = 0,
    LineArrow = 1,
    TriangleArrow = 2,
    SquareMarker = 3,
    CircleMarker = 4,
    DiamondMarker = 5,
    Round = 6,
    Square = 7,
}

impl From<u8> for RawStrokeCap {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl TryFrom<RawStrokeCap> for StrokeCap {
    type Error = ();

    fn try_from(value: RawStrokeCap) -> Result<Self, Self::Error> {
        match value {
            RawStrokeCap::None => Err(()),
            RawStrokeCap::LineArrow => Ok(StrokeCap::LineArrow),
            RawStrokeCap::TriangleArrow => Ok(StrokeCap::TriangleArrow),
            RawStrokeCap::SquareMarker => Ok(StrokeCap::SquareMarker),
            RawStrokeCap::CircleMarker => Ok(StrokeCap::CircleMarker),
            RawStrokeCap::DiamondMarker => Ok(StrokeCap::DiamondMarker),
            RawStrokeCap::Round => Ok(StrokeCap::Round),
            RawStrokeCap::Square => Ok(StrokeCap::Square),
        }
    }
}

// A negative value means "unset" — the renderer falls back to its default
// dash pattern. We use a sentinel instead of passing a bool because adding
// two f32 params keeps the FFI signature flat and allocation-free.
fn decode_optional(value: f32) -> Option<f32> {
    if value.is_finite() && value >= 0.0 {
        Some(value)
    } else {
        None
    }
}

#[no_mangle]
pub extern "C" fn add_shape_center_stroke(
    width: f32,
    style: u8,
    cap_start: u8,
    cap_end: u8,
    dash: f32,
    gap: f32,
) {
    let stroke_style = RawStrokeStyle::from(style);
    let cap_start = RawStrokeCap::from(cap_start);
    let cap_end = RawStrokeCap::from(cap_end);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_center_stroke(
            width,
            stroke_style.into(),
            cap_start.try_into().ok(),
            cap_end.try_into().ok(),
            decode_optional(dash),
            decode_optional(gap),
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_inner_stroke(
    width: f32,
    style: u8,
    cap_start: u8,
    cap_end: u8,
    dash: f32,
    gap: f32,
) {
    let stroke_style = RawStrokeStyle::from(style);
    let cap_start = RawStrokeCap::from(cap_start);
    let cap_end = RawStrokeCap::from(cap_end);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_inner_stroke(
            width,
            stroke_style.into(),
            cap_start.try_into().ok(),
            cap_end.try_into().ok(),
            decode_optional(dash),
            decode_optional(gap),
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_outer_stroke(
    width: f32,
    style: u8,
    cap_start: u8,
    cap_end: u8,
    dash: f32,
    gap: f32,
) {
    let stroke_style = RawStrokeStyle::from(style);
    let cap_start = RawStrokeCap::from(cap_start);
    let cap_end = RawStrokeCap::from(cap_end);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_outer_stroke(
            width,
            stroke_style.into(),
            cap_start.try_into().ok(),
            cap_end.try_into().ok(),
            decode_optional(dash),
            decode_optional(gap),
        ));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_stroke_sides(top: f32, right: f32, bottom: f32, left: f32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape
            .set_last_stroke_widths([top, right, bottom, left])
            .expect("could not set stroke side widths");
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
