use crate::shapes::{Color, SolidColor};

const RAW_SOLID_DATA_SIZE: usize = 4;

#[repr(C)]
#[repr(align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct RawSolidData {
    pub color: u32,
}

impl From<[u8; 4]> for RawSolidData {
    fn from(value: [u8; RAW_SOLID_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl TryFrom<&[u8]> for RawSolidData {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_SOLID_DATA_SIZE] = bytes
            .get(0..RAW_SOLID_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid solid fill data".to_string())?;
        Ok(RawSolidData::from(data))
    }
}

impl From<RawSolidData> for SolidColor {
    fn from(value: RawSolidData) -> Self {
        Self(Color::new(value.color))
    }
}

impl TryFrom<&[u8]> for SolidColor {
    type Error = String;

    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let raw_solid_bytes: [u8; 4] = bytes[0..4]
            .try_into()
            .map_err(|_| "Invalid solid fill data".to_string())?;
        let color = RawSolidData::from(raw_solid_bytes).into();

        Ok(color)
    }
}
