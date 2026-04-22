use macros::ToJs;

use crate::shapes::{Blur, BlurType};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawBlurType {
    LayerBlur = 0,
    BackgroundBlur = 1,
}

impl From<u8> for RawBlurType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawBlurType> for BlurType {
    fn from(value: RawBlurType) -> Self {
        match value {
            RawBlurType::LayerBlur => BlurType::LayerBlur,
            RawBlurType::BackgroundBlur => BlurType::BackgroundBlur,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_blur(blur_type: u8, hidden: bool, value: f32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let blur_type: BlurType = RawBlurType::from(blur_type).into();
        let blur = Blur::new(blur_type, hidden, value);
        shape.set_blur_of_kind(blur_type, Some(blur));
    });
}

/// Clears both blur slots. Use `clear_shape_blur_of_kind` for targeted
/// clearing when the shape carries both a layer blur and a background blur.
#[no_mangle]
pub extern "C" fn clear_shape_blur() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_blur(None);
        shape.set_background_blur(None);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_blur_of_kind(blur_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let blur_type: BlurType = RawBlurType::from(blur_type).into();
        shape.set_blur_of_kind(blur_type, None);
    });
}
