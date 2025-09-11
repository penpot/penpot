use macros::ToJs;

use super::Path;

#[derive(Debug, Clone, PartialEq)]
pub struct Bool {
    pub bool_type: BoolType,
    pub path: Path,
}

// TODO: maybe move this to the wasm module?
#[derive(Debug, Clone, Copy, PartialEq, ToJs)]
#[repr(u8)]
pub enum BoolType {
    Union = 0,
    Difference = 1,
    Intersection = 2,
    Exclusion = 3,
}

impl Default for Bool {
    fn default() -> Self {
        Bool {
            bool_type: BoolType::Union,
            path: Path::default(),
        }
    }
}

// TODO: maybe move this to the wasm module?
impl From<u8> for BoolType {
    // TODO: use transmute
    fn from(value: u8) -> Self {
        match value {
            0 => Self::Union,
            1 => Self::Difference,
            2 => Self::Intersection,
            3 => Self::Exclusion,
            _ => Self::default(),
        }
    }
}

impl Default for BoolType {
    fn default() -> Self {
        Self::Union
    }
}
