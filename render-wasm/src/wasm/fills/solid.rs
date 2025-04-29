use crate::shapes::{Color, SolidColor};

#[repr(C)]
pub struct RawSolidData {
    color: u32,
}

impl From<[u8; 4]> for RawSolidData {
    fn from(value: [u8; 4]) -> Self {
        Self {
            color: u32::from_le_bytes(value),
        }
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
