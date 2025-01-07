use crate::math;
use crate::shapes::fills::Fill;
use skia_safe as skia;

#[derive(Debug, Clone, PartialEq)]
pub enum StrokeStyle {
    Solid,
    Dotted,
    Dashed,
    Mixed,
}

impl From<i32> for StrokeStyle {
    fn from(value: i32) -> Self {
        match value {
            1 => StrokeStyle::Dotted,
            2 => StrokeStyle::Dashed,
            3 => StrokeStyle::Mixed,
            _ => StrokeStyle::Solid,
        }
    }
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
    kind: StrokeKind,
}

impl Stroke {
    // Strokes for open shapes should be rendered as if they were centered.
    pub fn render_kind(&self, is_open: bool) -> StrokeKind {
        if is_open {
            StrokeKind::CenterStroke
        } else {
            self.kind.clone()
        }
    }

    pub fn new_center_stroke(width: f32, style: i32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
            cap_end: StrokeCap::None,
            cap_start: StrokeCap::None,
            kind: StrokeKind::CenterStroke,
        }
    }

    pub fn new_inner_stroke(width: f32, style: i32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
            cap_end: StrokeCap::None,
            cap_start: StrokeCap::None,
            kind: StrokeKind::InnerStroke,
        }
    }

    pub fn new_outer_stroke(width: f32, style: i32) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
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

        if self.style != StrokeStyle::Solid {
            let path_effect = match self.style {
                StrokeStyle::Dotted => {
                    let mut circle_path = skia::Path::new();
                    circle_path.add_circle((0.0, 0.0), self.width / 2.0, None);
                    let advance = self.width + 5.0;
                    skia::PathEffect::path_1d(
                        &circle_path,
                        advance,
                        0.0,
                        skia::path_1d_path_effect::Style::Translate,
                    )
                }
                StrokeStyle::Dashed => {
                    skia::PathEffect::dash(&[self.width + 10., self.width + 10.], 0.)
                }
                StrokeStyle::Mixed => skia::PathEffect::dash(
                    &[
                        self.width + 5.,
                        self.width + 5.,
                        self.width + 1.,
                        self.width + 5.,
                    ],
                    0.,
                ),
                _ => None,
            };
            paint.set_path_effect(path_effect);
        }

        paint
    }

    pub fn to_stroked_paint(&self, kind: StrokeKind, rect: &math::Rect) -> skia::Paint {
        let mut paint = self.to_paint(rect);
        match kind {
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
