use crate::{shapes::ImageFill, utils::uuid_from_u32_quartet};

#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
#[repr(align(4))]
pub struct RawImageFillData {
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    opacity: f32,
    width: i32,
    height: i32,
}

impl From<RawImageFillData> for ImageFill {
    fn from(value: RawImageFillData) -> Self {
        let id = uuid_from_u32_quartet(value.a, value.b, value.c, value.d);
        let opacity = (value.opacity * 255.).floor() as u8;

        // TODO: read keep_aspect_ratio from RawImageFillData when implemented
        Self::new(id, opacity, value.width, value.height, true)
    }
}
