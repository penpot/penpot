use crate::math;
use crate::shapes::fills::Fill;
use skia_safe as skia;
use std::collections::HashMap;

use super::Corners;

#[derive(Debug, Clone, PartialEq)]
pub enum StrokeStyle {
    Solid,
    Dotted,
    Dashed,
    Mixed,
}

impl From<u8> for StrokeStyle {
    fn from(value: u8) -> Self {
        match value {
            1 => StrokeStyle::Dotted,
            2 => StrokeStyle::Dashed,
            3 => StrokeStyle::Mixed,
            _ => StrokeStyle::Solid,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum StrokeCap {
    None,
    Line,
    Triangle,
    Rectangle,
    Circle,
    Diamond,
    Round,
    Square,
}

impl From<u8> for StrokeCap {
    fn from(value: u8) -> Self {
        match value {
            1 => StrokeCap::Line,
            2 => StrokeCap::Triangle,
            3 => StrokeCap::Rectangle,
            4 => StrokeCap::Circle,
            5 => StrokeCap::Diamond,
            6 => StrokeCap::Round,
            7 => StrokeCap::Square,
            _ => StrokeCap::None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
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
    // Strokes for open shapes should be rendered as if they were centered.
    pub fn render_kind(&self, is_open: bool) -> StrokeKind {
        if is_open {
            StrokeKind::CenterStroke
        } else {
            self.kind
        }
    }

    pub fn new_center_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
            cap_end: StrokeCap::from(cap_end),
            cap_start: StrokeCap::from(cap_start),
            kind: StrokeKind::CenterStroke,
        }
    }

    pub fn new_inner_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
            cap_end: StrokeCap::from(cap_end),
            cap_start: StrokeCap::from(cap_start),
            kind: StrokeKind::InnerStroke,
        }
    }

    pub fn new_outer_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) -> Self {
        let transparent = skia::Color::from_argb(0, 0, 0, 0);
        Stroke {
            fill: Fill::Solid(transparent),
            width: width,
            style: StrokeStyle::from(style),
            cap_end: StrokeCap::from(cap_end),
            cap_start: StrokeCap::from(cap_start),
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

    pub fn outer_corners(&self, corners: &Corners) -> Corners {
        let offset = match self.kind {
            StrokeKind::CenterStroke => 0.0,
            StrokeKind::InnerStroke => -self.width / 2.0,
            StrokeKind::OuterStroke => self.width / 2.0,
        };

        let mut outer = corners.clone();
        for corner in outer.iter_mut() {
            corner.offset((offset, offset))
        }
        outer
    }

    pub fn to_paint(
        &self,
        rect: &math::Rect,
        svg_attrs: &HashMap<String, String>,
        scale: f32,
    ) -> skia::Paint {
        let mut paint = self.fill.to_paint(rect);
        paint.set_style(skia::PaintStyle::Stroke);

        let width = match self.kind {
            StrokeKind::InnerStroke => self.width,
            StrokeKind::CenterStroke => self.width,
            StrokeKind::OuterStroke => self.width + (1. / scale),
        };

        paint.set_stroke_width(width);
        paint.set_anti_alias(true);

        if let Some("round") = svg_attrs.get("stroke-linecap").map(String::as_str) {
            paint.set_stroke_cap(skia::paint::Cap::Round);
        }

        if let Some("round") = svg_attrs.get("stroke-linejoin").map(String::as_str) {
            paint.set_stroke_join(skia::paint::Join::Round);
        }

        if self.style != StrokeStyle::Solid {
            let path_effect = match self.style {
                StrokeStyle::Dotted => {
                    let mut circle_path = skia::Path::new();
                    let width = match self.kind {
                        StrokeKind::InnerStroke => self.width,
                        StrokeKind::CenterStroke => self.width / 2.0,
                        StrokeKind::OuterStroke => self.width,
                    };
                    circle_path.add_circle((0.0, 0.0), width, None);
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

    pub fn to_stroked_paint(
        &self,
        is_open: bool,
        rect: &math::Rect,
        svg_attrs: &HashMap<String, String>,
        scale: f32,
    ) -> skia::Paint {
        let mut paint = self.to_paint(rect, svg_attrs, scale);
        match self.render_kind(is_open) {
            StrokeKind::InnerStroke => {
                paint.set_stroke_width(2. * paint.stroke_width());
                paint
            }
            StrokeKind::CenterStroke => paint,
            StrokeKind::OuterStroke => {
                paint.set_stroke_width(2. * paint.stroke_width());
                paint
            }
        }
    }
}
