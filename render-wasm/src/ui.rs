use std::cmp::Ordering;

pub use crate::math::Rect;
pub use crate::shapes::Color;

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
    /// When the guide belongs to a board, the `[start, end]` range (along the
    /// guide's line direction) of that board. The guide is drawn solid only
    /// within this range and trimmed outside it. `None` for free guides, which
    /// span the whole viewport.
    pub frame_range: Option<(f32, f32)>,
}

impl Guide {
    pub fn new(
        kind: GuideKind,
        color: Color,
        index: Option<usize>,
        frame_range: Option<(f32, f32)>,
    ) -> Self {
        Self {
            kind,
            color,
            index: index.unwrap_or_default(),
            frame_range,
        }
    }

    pub fn position(&self) -> f32 {
        self.kind.value()
    }

    /// Whether `along` (the coordinate along the guide line: y for vertical
    /// guides, x for horizontal) falls inside this guide's interactive
    /// segment. Free guides (`frame_range = None`) accept any value; board-
    /// bound guides only accept points inside their clipped range.
    pub fn contains_along(&self, along: f32) -> bool {
        match self.frame_range {
            None => true,
            Some((start, end)) => {
                let (lo, hi) = if start <= end {
                    (start, end)
                } else {
                    (end, start)
                };
                along >= lo && along <= hi
            }
        }
    }
}
