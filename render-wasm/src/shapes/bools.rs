use super::Path;

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Bool {
    pub bool_type: BoolType,
    pub path: Path,
}

#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub enum BoolType {
    #[default]
    Union,
    Difference,
    Intersection,
    Exclusion,
}
