use crate::{shapes::ImageFill, utils::uuid_from_u32_quartet};

const FLAG_KEEP_ASPECT_RATIO: u8 = 1 << 0;

#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
#[repr(align(4))]
pub struct RawImageFillData {
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    opacity: u8,
    flags: u8,
    // 16-bit padding here, reserved for future use
    width: i32,
    height: i32,
}

impl From<RawImageFillData> for ImageFill {
    fn from(value: RawImageFillData) -> Self {
        let id = uuid_from_u32_quartet(value.a, value.b, value.c, value.d);
        let keep_aspect_ratio = value.flags & FLAG_KEEP_ASPECT_RATIO != 0;

        Self::new(
            id,
            value.opacity,
            value.width,
            value.height,
            keep_aspect_ratio,
        )
    }
}
