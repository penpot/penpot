use crate::math;
use crate::shapes::fills::Fill;
use skia_safe as skia;

#[derive(Debug, Clone, PartialEq)]
pub enum StrokeStyle {
    Solid,
    // Dotted,
    // Dashed,
    // Mixed,
}

#[derive(Debug, Clone, PartialEq)]
pub enum StrokeCap {
    None,
    // Line,
    // Triangle,
    // Circle,
    // Diamond,
    // Round,
    // Square,
}

#[derive(Debug, Clone, PartialEq)]
pub enum StrokeKind {
    InnerStroke,
    OuterStroke,
    CenterStroke,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Stroke {
    pub fill: Fill,
    pub width: f32,
    pub style: StrokeStyle,
    pub cap_end: StrokeCap,
    pub cap_start: StrokeCap,
    pub kind: StrokeKind,
}

impl Stroke {
    pub fn new_center_stroke(width: f32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::Solid,
            cap_end: StrokeCap::None,
            cap_start: StrokeCap::None,
            kind: StrokeKind::CenterStroke,
        }
    }

    pub fn new_inner_stroke(width: f32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::Solid,
            cap_end: StrokeCap::None,
            cap_start: StrokeCap::None,
            kind: StrokeKind::InnerStroke,
        }
    }

    pub fn new_outer_stroke(width: f32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::Solid,
            cap_end: StrokeCap::None,
            cap_start: StrokeCap::None,
            kind: StrokeKind::OuterStroke,
        }
    }

    pub fn delta(&self) -> f32 {
        match self.kind {
            StrokeKind::InnerStroke => 0.,
            StrokeKind::CenterStroke => self.width / 2.,
            StrokeKind::OuterStroke => self.width,
        }
    }

    pub fn outer_rect(&self, rect: &math::Rect) -> math::Rect {
        match self.kind {
            StrokeKind::InnerStroke => math::Rect::from_xywh(
                rect.left + (self.width / 2.),
                rect.top + (self.width / 2.),
                rect.width() - self.width,
                rect.height() - self.width,
            ),
            StrokeKind::CenterStroke => {
                math::Rect::from_xywh(rect.left, rect.top, rect.width(), rect.height())
            }
            StrokeKind::OuterStroke => math::Rect::from_xywh(
                rect.left - (self.width / 2.),
                rect.top - (self.width / 2.),
                rect.width() + self.width,
                rect.height() + self.width,
            ),
        }
    }

    pub fn to_paint(&self, rect: &math::Rect) -> skia::Paint {
        let mut paint = self.fill.to_paint(rect);
        paint.set_style(skia::PaintStyle::Stroke);
        paint.set_stroke_width(self.width);
        paint.set_anti_alias(true);
        paint
    }

    pub fn to_stroked_paint(&self, rect: &math::Rect) -> skia::Paint {
        let mut paint = self.to_paint(rect);
        match self.kind {
            StrokeKind::InnerStroke => {
                paint.set_stroke_width(2. * self.width);
                paint
            }

            StrokeKind::CenterStroke => paint,
            StrokeKind::OuterStroke => {
                paint.set_stroke_width(2. * self.width);
                paint
            }
        }
    }
}
