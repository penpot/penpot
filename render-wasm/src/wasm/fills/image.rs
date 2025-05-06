use crate::{shapes::ImageFill, utils::uuid_from_u32_quartet};

const RAW_IMAGE_DATA_SIZE: usize = 28;

#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
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

        Self::new(id, opacity, value.width, value.height)
    }
}

impl From<[u8; RAW_IMAGE_DATA_SIZE]> for RawImageFillData {
    fn from(value: [u8; RAW_IMAGE_DATA_SIZE]) -> Self {
        let a = u32::from_le_bytes([value[0], value[1], value[2], value[3]]);
        let b = u32::from_le_bytes([value[4], value[5], value[6], value[7]]);
        let c = u32::from_le_bytes([value[8], value[9], value[10], value[11]]);
        let d = u32::from_le_bytes([value[12], value[13], value[14], value[15]]);
        let opacity = f32::from_le_bytes([value[16], value[17], value[18], value[19]]);
        let width = i32::from_le_bytes([value[20], value[21], value[22], value[23]]);
        let height = i32::from_le_bytes([value[24], value[25], value[26], value[27]]);

        Self {
            a,
            b,
            c,
            d,
            opacity,
            width,
            height,
        }
    }
}

impl TryFrom<&[u8]> for RawImageFillData {
    type Error = String;

    fn try_from(value: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_IMAGE_DATA_SIZE] = value
            .get(0..RAW_IMAGE_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid image fill data".to_string())?;
        Ok(Self::from(data))
    }
}

impl TryFrom<&[u8]> for ImageFill {
    type Error = String;

    fn try_from(value: &[u8]) -> Result<Self, Self::Error> {
        let raw_image_data = RawImageFillData::try_from(value)?;
        Ok(raw_image_data.into())
    }
}
