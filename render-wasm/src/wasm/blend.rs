use macros::ToJs;
use skia_safe as skia;

use crate::shapes::BlendMode;
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawBlendMode {
    Normal = 3,
    Screen = 14,
    Overlay = 15,
    Darken = 16,
    Lighten = 17,
    ColorDodge = 18,
    ColorBurn = 19,
    HardLight = 20,
    SoftLight = 21,
    Difference = 22,
    Exclusion = 23,
    Multiply = 24,
    Hue = 25,
    Saturation = 26,
    Color = 27,
    Luminosity = 28,
}

impl From<u8> for RawBlendMode {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawBlendMode> for BlendMode {
    fn from(value: RawBlendMode) -> Self {
        match value {
            RawBlendMode::Normal => BlendMode(skia::BlendMode::SrcOver),
            RawBlendMode::Screen => BlendMode(skia::BlendMode::Screen),
            RawBlendMode::Overlay => BlendMode(skia::BlendMode::Overlay),
            RawBlendMode::Darken => BlendMode(skia::BlendMode::Darken),
            RawBlendMode::Lighten => BlendMode(skia::BlendMode::Lighten),
            RawBlendMode::ColorDodge => BlendMode(skia::BlendMode::ColorDodge),
            RawBlendMode::ColorBurn => BlendMode(skia::BlendMode::ColorBurn),
            RawBlendMode::HardLight => BlendMode(skia::BlendMode::HardLight),
            RawBlendMode::SoftLight => BlendMode(skia::BlendMode::SoftLight),
            RawBlendMode::Difference => BlendMode(skia::BlendMode::Difference),
            RawBlendMode::Exclusion => BlendMode(skia::BlendMode::Exclusion),
            RawBlendMode::Multiply => BlendMode(skia::BlendMode::Multiply),
            RawBlendMode::Hue => BlendMode(skia::BlendMode::Hue),
            RawBlendMode::Saturation => BlendMode(skia::BlendMode::Saturation),
            RawBlendMode::Color => BlendMode(skia::BlendMode::Color),
            RawBlendMode::Luminosity => BlendMode(skia::BlendMode::Luminosity),
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_blend_mode(mode: u8) {
    let mode = RawBlendMode::from(mode);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_blend_mode(mode.into());
    });
}
