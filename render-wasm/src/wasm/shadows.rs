use macros::ToJs;
use skia_safe as skia;

use crate::shapes::{Shadow, ShadowStyle};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawShadowStyle {
    // NOTE: Odd naming to comply with cljs value
    DropShadow = 0,
    InnerShadow = 1,
}

impl From<u8> for RawShadowStyle {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawShadowStyle> for ShadowStyle {
    fn from(value: RawShadowStyle) -> Self {
        match value {
            RawShadowStyle::DropShadow => Self::Drop,
            RawShadowStyle::InnerShadow => Self::Inner,
        }
    }
}

#[no_mangle]
pub extern "C" fn add_shape_shadow(
    raw_color: u32,
    blur: f32,
    spread: f32,
    x: f32,
    y: f32,
    raw_style: u8,
    hidden: bool,
) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        let style = RawShadowStyle::from(raw_style).into();
        let shadow = Shadow::new(color, blur, spread, (x, y), style, hidden);
        shape.add_shadow(shadow);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_shadows() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_shadows();
    });
}
