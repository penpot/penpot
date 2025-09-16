use super::Path;

#[derive(Debug, Clone, PartialEq)]
pub struct Bool {
    pub bool_type: BoolType,
    pub path: Path,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BoolType {
    Union,
    Difference,
    Intersection,
    Exclusion,
}

impl Default for Bool {
    fn default() -> Self {
        Bool {
            bool_type: BoolType::Union,
            path: Path::default(),
        }
    }
}

impl Default for BoolType {
    fn default() -> Self {
        Self::Union
    }
}
