use macros::ToJs;

use crate::shapes::{Blur, BlurType};
use crate::with_current_shape_mut;

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
        let blur = Some(Blur::new(blur_type, hidden, value));
        match blur_type {
            BlurType::LayerBlur => shape.set_blur(blur),
            BlurType::BackgroundBlur => shape.set_background_blur(blur),
        }
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_blur(blur_type: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        match RawBlurType::from(blur_type).into() {
            BlurType::LayerBlur => shape.set_blur(None),
            BlurType::BackgroundBlur => shape.set_background_blur(None),
        }
    });
}
