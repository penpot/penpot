use super::Corners;

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Rect {
    pub corners: Option<Corners>,
}
