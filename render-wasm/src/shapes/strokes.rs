use crate::shapes::fills::{Fill, SolidColor};
use skia_safe::{self as skia, Rect};

use super::Corners;
use super::StrokeLineCap;
use super::StrokeLineJoin;
use super::SvgAttrs;

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum StrokeStyle {
    Solid,
    Dotted,
    Dashed,
    Mixed,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum StrokeCap {
    LineArrow,
    TriangleArrow,
    SquareMarker,
    CircleMarker,
    DiamondMarker,
    Round,
    Square,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum StrokeKind {
    Inner,
    Outer,
    Center,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Stroke {
    pub fill: Fill,
    pub width: f32,
    pub style: StrokeStyle,
    pub cap_end: Option<StrokeCap>,
    pub cap_start: Option<StrokeCap>,
    pub kind: StrokeKind,
}

impl Stroke {
    // Strokes for open shapes should be rendered as if they were centered.
    pub fn render_kind(&self, is_open: bool) -> StrokeKind {
        if is_open {
            StrokeKind::Center
        } else {
            self.kind
        }
    }

    pub fn bounds_width(&self, is_open: bool) -> f32 {
        match self.render_kind(is_open) {
            StrokeKind::Inner => 0.,
            StrokeKind::Center => self.width / 2.,
            StrokeKind::Outer => self.width,
        }
    }

    pub fn max_bounds_width<'a>(strokes: impl Iterator<Item = &'a Stroke>, is_open: bool) -> f32 {
        strokes
            .map(|stroke| stroke.bounds_width(is_open))
            .fold(0.0, f32::max)
    }

    pub fn new_center_stroke(
        width: f32,
        style: StrokeStyle,
        cap_start: Option<StrokeCap>,
        cap_end: Option<StrokeCap>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Center,
        }
    }

    pub fn new_inner_stroke(
        width: f32,
        style: StrokeStyle,
        cap_start: Option<StrokeCap>,
        cap_end: Option<StrokeCap>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Inner,
        }
    }

    pub fn new_outer_stroke(
        width: f32,
        style: StrokeStyle,
        cap_start: Option<StrokeCap>,
        cap_end: Option<StrokeCap>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Outer,
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.width *= value;
    }

    pub fn delta(&self) -> f32 {
        match self.kind {
            StrokeKind::Inner => 0.,
            StrokeKind::Center => self.width,
            StrokeKind::Outer => self.width * 2.,
        }
    }

    pub fn outer_rect(&self, rect: &Rect) -> Rect {
        match self.kind {
            StrokeKind::Inner => Rect::from_xywh(
                rect.left + (self.width / 2.),
                rect.top + (self.width / 2.),
                rect.width() - self.width,
                rect.height() - self.width,
            ),
            StrokeKind::Center => Rect::from_xywh(rect.left, rect.top, rect.width(), rect.height()),
            StrokeKind::Outer => Rect::from_xywh(
                rect.left - (self.width / 2.),
                rect.top - (self.width / 2.),
                rect.width() + self.width,
                rect.height() + self.width,
            ),
        }
    }

    pub fn outer_corners(&self, corners: &Corners) -> Corners {
        let offset = match self.kind {
            StrokeKind::Center => 0.0,
            StrokeKind::Inner => -self.width / 2.0,
            StrokeKind::Outer => self.width / 2.0,
        };

        let mut outer = *corners;
        for corner in outer.iter_mut() {
            corner.offset((offset, offset))
        }
        outer
    }

    pub fn to_paint(
        &self,
        rect: &Rect,
        svg_attrs: Option<&SvgAttrs>,
        scale: f32,
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.fill.to_paint(rect, antialias);
        paint.set_style(skia::PaintStyle::Stroke);

        let width = match self.kind {
            StrokeKind::Inner => self.width,
            StrokeKind::Center => self.width,
            StrokeKind::Outer => self.width + (1. / scale),
        };

        paint.set_stroke_width(width);
        paint.set_anti_alias(antialias);

        if let Some(svg_attrs) = svg_attrs {
            if svg_attrs.stroke_linecap == StrokeLineCap::Round {
                paint.set_stroke_cap(skia::paint::Cap::Round);
            }

            if svg_attrs.stroke_linejoin == StrokeLineJoin::Round {
                paint.set_stroke_join(skia::paint::Join::Round);
            }
        }

        if self.style != StrokeStyle::Solid {
            let path_effect = match self.style {
                StrokeStyle::Dotted => {
                    let mut circle_path = skia::Path::new();
                    let width = match self.kind {
                        StrokeKind::Inner => self.width,
                        StrokeKind::Center => self.width / 2.0,
                        StrokeKind::Outer => self.width,
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
        rect: &Rect,
        svg_attrs: Option<&SvgAttrs>,
        scale: f32,
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.to_paint(rect, svg_attrs, scale, antialias);
        match self.render_kind(is_open) {
            StrokeKind::Inner => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
            StrokeKind::Center => {}
            StrokeKind::Outer => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
        }

        paint
    }

    // Render text paths (unused)
    #[allow(dead_code)]
    pub fn to_text_stroked_paint(
        &self,
        is_open: bool,
        rect: &Rect,
        svg_attrs: Option<&SvgAttrs>,
        scale: f32,
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.to_paint(rect, svg_attrs, scale, antialias);
        match self.render_kind(is_open) {
            StrokeKind::Inner => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
            StrokeKind::Center => {}
            StrokeKind::Outer => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
        }

        paint
    }

    pub fn is_transparent(&self) -> bool {
        match &self.fill {
            Fill::Solid(SolidColor(color)) => color.a() == 0,
            _ => false,
        }
    }

    pub fn cap_bounds_margin(&self) -> f32 {
        cap_margin_for_cap(self.cap_start, self.width)
            .max(cap_margin_for_cap(self.cap_end, self.width))
    }
}

fn cap_margin_for_cap(cap: Option<StrokeCap>, width: f32) -> f32 {
    match cap {
        Some(StrokeCap::LineArrow)
        | Some(StrokeCap::TriangleArrow)
        | Some(StrokeCap::SquareMarker)
        | Some(StrokeCap::DiamondMarker) => width * 4.0,
        Some(StrokeCap::CircleMarker) => width * 2.0,
        Some(StrokeCap::Square) => width,
        Some(StrokeCap::Round) => width * 0.5,
        _ => 0.0,
    }
}
