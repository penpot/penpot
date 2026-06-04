use std::cmp::Ordering;

use crate::shapes::Color;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum GuideKind {
    Vertical(f32),
    Horizontal(f32),
}

impl GuideKind {
    fn value(self) -> f32 {
        match self {
            GuideKind::Vertical(x) => x,
            GuideKind::Horizontal(y) => y,
        }
    }
}

impl PartialOrd for GuideKind {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        self.value().partial_cmp(&other.value())
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Guide {
    pub kind: GuideKind,
    pub color: Color,
    /// Index of the guide in the guide list (clojure side)
    pub index: usize,
}

impl Guide {
    pub fn new(kind: GuideKind, color: Color, index: Option<usize>) -> Self {
        Self {
            kind,
            color,
            index: index.unwrap_or_default(),
        }
    }

    pub fn position(&self) -> f32 {
        self.kind.value()
    }
}
