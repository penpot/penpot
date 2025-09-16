use macros::ToJs;

use crate::shapes::{Blur, BlurType};
use crate::{with_current_shape_mut, STATE};

#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawBlurType {
    LayerBlur = 0, // odd naming to comply with cljs value
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
        }
    }
}

#[no_mangle]
pub extern "C" fn set_shape_blur(blur_type: u8, hidden: bool, value: f32) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let blur_type = RawBlurType::from(blur_type);
        shape.set_blur(Some(Blur::new(blur_type.into(), hidden, value)));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_blur() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_blur(None);
    });
}
