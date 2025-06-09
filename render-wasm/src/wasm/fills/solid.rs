use crate::shapes::{Color, SolidColor};

#[repr(C)]
#[repr(align(4))]
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct RawSolidData {
    pub color: u32,
}

impl From<RawSolidData> for SolidColor {
    fn from(value: RawSolidData) -> Self {
        Self(Color::new(value.color))
    }
}
