use crate::math::is_close_to;
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
    // Dash and gap overrides for the `Dashed` style. `None` falls back to the
    // default `width + 10` pattern to keep existing designs visually identical.
    pub dash: Option<f32>,
    pub gap: Option<f32>,
    // Per-side widths [top, right, bottom, left] for rects and frames.
    // `None` means the uniform `width` applies to all sides.
    pub widths: Option<[f32; 4]>,
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

    /// The widest side of the stroke: the uniform `width` unless per-side
    /// widths are set, in which case the maximum of the four sides.
    pub fn max_width(&self) -> f32 {
        match self.widths {
            Some(widths) => widths.into_iter().reduce(f32::max).unwrap_or(self.width),
            None => self.width,
        }
    }

    /// Per-side widths [top, right, bottom, left] when they actually differ.
    /// Returns `None` when unset or when all sides are equal, so the uniform
    /// render path (which supports dashed/dotted styles) keeps handling that
    /// case.
    pub fn per_side_widths(&self) -> Option<[f32; 4]> {
        let widths = self.widths?;
        let [top, right, bottom, left] = widths;
        if top == right && right == bottom && bottom == left {
            None
        } else {
            Some(widths)
        }
    }

    pub fn bounds_width(&self, is_open: bool) -> f32 {
        match self.render_kind(is_open) {
            StrokeKind::Inner => 0.,
            StrokeKind::Center => self.max_width() / 2.,
            StrokeKind::Outer => self.max_width(),
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
        dash: Option<f32>,
        gap: Option<f32>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Center,
            dash,
            gap,
            widths: None,
        }
    }

    pub fn new_inner_stroke(
        width: f32,
        style: StrokeStyle,
        cap_start: Option<StrokeCap>,
        cap_end: Option<StrokeCap>,
        dash: Option<f32>,
        gap: Option<f32>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Inner,
            dash,
            gap,
            widths: None,
        }
    }

    pub fn new_outer_stroke(
        width: f32,
        style: StrokeStyle,
        cap_start: Option<StrokeCap>,
        cap_end: Option<StrokeCap>,
        dash: Option<f32>,
        gap: Option<f32>,
    ) -> Self {
        Stroke {
            fill: Fill::Solid(SolidColor(skia::Color::TRANSPARENT)),
            width,
            style,
            cap_end,
            cap_start,
            kind: StrokeKind::Outer,
            dash,
            gap,
            widths: None,
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.width *= value;
        if let Some(widths) = &mut self.widths {
            for width in widths.iter_mut() {
                *width *= value;
            }
        }
        if let Some(dash) = self.dash {
            self.dash = Some(dash * value);
        }
        if let Some(gap) = self.gap {
            self.gap = Some(gap * value);
        }
    }

    /// Returns the clip operation for dotted inner/outer strokes.
    /// Returns `None` when no clipping is needed (center or non-dotted).
    pub fn clip_op(&self) -> Option<skia::ClipOp> {
        if self.style != StrokeStyle::Dotted || self.kind == StrokeKind::Center {
            return None;
        }
        match self.kind {
            StrokeKind::Inner => Some(skia::ClipOp::Intersect),
            StrokeKind::Outer => Some(skia::ClipOp::Difference),
            StrokeKind::Center => None,
        }
    }

    pub fn delta(&self) -> f32 {
        match self.kind {
            StrokeKind::Inner => 0.,
            StrokeKind::Center => self.width,
            StrokeKind::Outer => self.width * 2.,
        }
    }

    pub fn outer_rect(&self, rect: &Rect) -> Rect {
        match (self.kind, self.style) {
            (StrokeKind::Inner, StrokeStyle::Dotted) | (StrokeKind::Outer, StrokeStyle::Dotted) => {
                // Boundary so circles center on it and semicircles match after clipping
                *rect
            }
            _ => match self.kind {
                StrokeKind::Inner => Rect::from_xywh(
                    rect.left + (self.width / 2.),
                    rect.top + (self.width / 2.),
                    rect.width() - self.width,
                    rect.height() - self.width,
                ),
                StrokeKind::Center => {
                    Rect::from_xywh(rect.left, rect.top, rect.width(), rect.height())
                }
                StrokeKind::Outer => Rect::from_xywh(
                    rect.left - (self.width / 2.),
                    rect.top - (self.width / 2.),
                    rect.width() + self.width,
                    rect.height() + self.width,
                ),
            },
        }
    }

    pub fn aligned_rect(&self, rect: &Rect, scale: f32) -> Rect {
        let stroke_rect = self.outer_rect(rect);
        if self.kind != StrokeKind::Center {
            return stroke_rect;
        }

        align_rect_to_half_pixel(&stroke_rect, self.width, scale)
    }

    pub fn outer_corners(&self, corners: &Corners) -> Corners {
        if matches!(self.style, StrokeStyle::Dotted | StrokeStyle::Dashed) {
            // Path at boundary so no corner offset
            return *corners;
        }

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
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.fill.to_paint(rect, antialias);
        paint.set_style(skia::PaintStyle::Stroke);

        let width = match self.kind {
            StrokeKind::Inner => self.width,
            StrokeKind::Center => self.width,
            StrokeKind::Outer => self.width,
        };

        paint.set_stroke_width(width);
        paint.set_anti_alias(antialias);

        if let Some(svg_attrs) = svg_attrs {
            match svg_attrs.stroke_linecap {
                StrokeLineCap::Round => {
                    paint.set_stroke_cap(skia::paint::Cap::Round);
                }
                StrokeLineCap::Square => {
                    paint.set_stroke_cap(skia::paint::Cap::Square);
                }
                StrokeLineCap::Butt => {} // Skia default
            }

            match svg_attrs.stroke_linejoin {
                StrokeLineJoin::Round => {
                    paint.set_stroke_join(skia::paint::Join::Round);
                }
                StrokeLineJoin::Bevel => {
                    paint.set_stroke_join(skia::paint::Join::Bevel);
                }
                StrokeLineJoin::Miter => {} // Skia default
            }
        }

        if self.style != StrokeStyle::Solid {
            let path_effect = match self.style {
                StrokeStyle::Dotted => {
                    let width = match self.kind {
                        StrokeKind::Inner => self.width,
                        StrokeKind::Center => self.width / 2.0,
                        StrokeKind::Outer => self.width,
                    };
                    let circle_path = {
                        let mut pb = skia::PathBuilder::new();
                        pb.add_circle((0.0, 0.0), width, None);
                        pb.detach()
                    };
                    let advance = self.width + 5.0;
                    skia::PathEffect::path_1d(
                        &circle_path,
                        advance,
                        0.0,
                        skia::path_1d_path_effect::Style::Translate,
                    )
                }
                StrokeStyle::Dashed => {
                    let dash = self.dash.unwrap_or(self.width + 10.);
                    let gap = self.gap.unwrap_or(self.width + 10.);
                    skia::PathEffect::dash(&[dash, gap], 0.)
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
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.to_paint(rect, svg_attrs, antialias);
        match self.render_kind(is_open) {
            StrokeKind::Inner => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
            StrokeKind::Center => {}
            StrokeKind::Outer => {
                paint.set_stroke_width(2. * paint.stroke_width());
            }
        }

        if let Some(cap) = self.to_skia_linecap() {
            paint.set_stroke_cap(cap);
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
        antialias: bool,
    ) -> skia::Paint {
        let mut paint = self.to_paint(rect, svg_attrs, antialias);
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

    /// Returns a Skia `PaintCap` to apply natively on the stroke paint when
    /// both ends share the same simple line cap (`Round/Round` or
    /// `Square/Square`). Skia only emits cap geometry at sub-path endpoints,
    /// so this is a no-op on closed paths and avoids the extra fill draw the
    /// manual caps would otherwise require on open paths.
    pub fn to_skia_linecap(&self) -> Option<skia::paint::Cap> {
        match (self.cap_start, self.cap_end) {
            (Some(StrokeCap::Round), Some(StrokeCap::Round)) => Some(skia::paint::Cap::Round),
            (Some(StrokeCap::Square), Some(StrokeCap::Square)) => Some(skia::paint::Cap::Square),
            _ => None,
        }
    }
}

fn align_rect_to_half_pixel(rect: &Rect, stroke_width: f32, scale: f32) -> Rect {
    if scale <= 0.0 {
        return *rect;
    }

    let stroke_pixels = stroke_width * scale;
    let stroke_pixels_rounded = stroke_pixels.round();
    if !is_close_to(stroke_pixels, stroke_pixels_rounded) {
        return *rect;
    }

    if (stroke_pixels_rounded as i32) % 2 == 0 {
        return *rect;
    }

    let left_px = rect.left * scale;
    let top_px = rect.top * scale;
    let target_frac = 0.5;
    let dx_px = target_frac - (left_px - left_px.floor());
    let dy_px = target_frac - (top_px - top_px.floor());

    if is_close_to(dx_px, 0.0) && is_close_to(dy_px, 0.0) {
        return *rect;
    }

    Rect::from_xywh(
        rect.left + (dx_px / scale),
        rect.top + (dy_px / scale),
        rect.width(),
        rect.height(),
    )
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

#[cfg(test)]
mod tests {
    use super::*;

    fn stroke_with_widths(widths: Option<[f32; 4]>) -> Stroke {
        let mut stroke = Stroke::new_inner_stroke(2.0, StrokeStyle::Solid, None, None, None, None);
        stroke.widths = widths;
        stroke
    }

    #[test]
    fn max_width_falls_back_to_uniform_width() {
        let stroke = stroke_with_widths(None);
        assert_eq!(stroke.max_width(), 2.0);
    }

    #[test]
    fn max_width_uses_widest_side() {
        let stroke = stroke_with_widths(Some([1.0, 8.0, 3.0, 0.0]));
        assert_eq!(stroke.max_width(), 8.0);
    }

    #[test]
    fn per_side_widths_none_when_all_sides_equal() {
        let stroke = stroke_with_widths(Some([4.0, 4.0, 4.0, 4.0]));
        assert_eq!(stroke.per_side_widths(), None);
    }

    #[test]
    fn per_side_widths_returns_differing_sides() {
        let stroke = stroke_with_widths(Some([1.0, 2.0, 3.0, 4.0]));
        assert_eq!(stroke.per_side_widths(), Some([1.0, 2.0, 3.0, 4.0]));
    }

    #[test]
    fn scale_content_scales_per_side_widths() {
        let mut stroke = stroke_with_widths(Some([1.0, 2.0, 3.0, 4.0]));
        stroke.scale_content(2.0);
        assert_eq!(stroke.widths, Some([2.0, 4.0, 6.0, 8.0]));
        assert_eq!(stroke.width, 4.0);
    }
}
