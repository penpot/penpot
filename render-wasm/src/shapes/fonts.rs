use std::fmt;

use crate::uuid::Uuid;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum FontStyle {
    Normal,
    Italic,
}

impl From<u8> for FontStyle {
    fn from(value: u8) -> Self {
        match value {
            0 => Self::Normal,
            1 => Self::Italic,
            _ => Self::Normal,
        }
    }
}

impl fmt::Display for FontStyle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let txt = match self {
            Self::Normal => "normal",
            Self::Italic => "italic",
        };
        write!(f, "{}", txt)
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct FontFamily {
    id: Uuid,
    style: FontStyle,
    weight: u32,
}

impl FontFamily {
    pub fn new(id: Uuid, weight: u32, style: FontStyle) -> Self {
        Self { id, style, weight }
    }

    pub fn alias(&self) -> String {
        format!("{}", self)
    }
}

impl fmt::Display for FontFamily {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {} {}", self.id, self.weight, self.style)
    }
}
