use crate::shapes::Color;

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum GuideKind {
    Vertical(f32),
    Horizontal(f32),
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct Guide {
    pub kind: GuideKind,
    pub color: Color,
}

impl Guide {
    pub fn new(kind: GuideKind, color: Color) -> Self {
        Self { kind, color }
    }
}
