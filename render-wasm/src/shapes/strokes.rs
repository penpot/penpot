use crate::math::is_close_to;
use crate::shapes::fills::{Fill, SolidColor};
use skia_safe::{self as skia, Rect};

use super::Corners;
use super::StrokeLineCap;
use super::StrokeLineJoin;
use super::SvgAttrs;

/// Soft floor in device pixels for dropping dash/dotted PathEffects when the
/// pattern period is effectively invisible.
pub const STROKE_MIN_DEVICE_PX: f32 = 0.75;

/// When Inner/Outer doubled-width footprint is below this (device px), paint
/// as Center to avoid save_layer / Clear paths.
pub const STROKE_INNER_OUTER_SIMPLIFY_DEVICE_PX: f32 = 2.0;

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

/// A rect side, in the order `Stroke::widths` stores them. Side `n` runs
/// clockwise from corner `n` in `Corners` order, sharing its index.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Top,
    Right,
    Bottom,
    Left,
}

impl Side {
    pub const ALL: [Side; 4] = [Side::Top, Side::Right, Side::Bottom, Side::Left];

    pub fn index(self) -> usize {
        self as usize
    }
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
    // Per-side widths in `Side` order. `None` means the uniform `width` applies
    // to all sides.
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

    /// Inner/Outer use a doubled-width Center stroke plus clip/clear. When that
    /// footprint is thin on screen, fall back to a plain Center stroke.
    #[inline]
    pub fn simplified_kind_at_scale(&self, is_open: bool, scale: f32) -> StrokeKind {
        let kind = self.render_kind(is_open);
        match kind {
            StrokeKind::Inner | StrokeKind::Outer
                if 2.0 * self.max_width() * scale < STROKE_INNER_OUTER_SIMPLIFY_DEVICE_PX =>
            {
                StrokeKind::Center
            }
            other => other,
        }
    }

    /// Drop dash/dotted PathEffects when the pattern period is subpixel.
    #[inline]
    pub fn style_at_scale(&self, scale: f32) -> StrokeStyle {
        if self.style == StrokeStyle::Solid {
            return StrokeStyle::Solid;
        }
        let period = match self.style {
            StrokeStyle::Dotted => self.width + 5.0,
            StrokeStyle::Dashed => {
                let dash = self.dash.unwrap_or(self.width + 10.);
                let gap = self.gap.unwrap_or(self.width + 10.);
                dash.min(gap)
            }
            StrokeStyle::Mixed => self.width + 1.0,
            StrokeStyle::Solid => return StrokeStyle::Solid,
        };
        if period * scale < STROKE_MIN_DEVICE_PX {
            StrokeStyle::Solid
        } else {
            self.style
        }
    }

    /// Path/Bool overview LOD: simplify Inner/Outer and dash/dotted at low
    /// scale. Never skips painting; stroke-only icons would otherwise go blank.
    pub fn path_lod_at_scale(&self, is_open: bool, scale: f32) -> Stroke {
        let kind = self.simplified_kind_at_scale(is_open, scale);
        let style = self.style_at_scale(scale);
        let kind_unchanged = kind == self.render_kind(is_open);
        let style_unchanged = style == self.style;
        if kind_unchanged && style_unchanged {
            return self.clone();
        }
        let mut stroke = self.clone();
        if !is_open {
            stroke.kind = kind;
        }
        stroke.style = style;
        stroke
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

    /// The widest each side reaches across a shape's per-side strokes, which is
    /// the border box they miter against. `None` when at most one stroke needs it.
    pub fn per_side_profile<'a>(strokes: impl Iterator<Item = &'a Stroke>) -> Option<[f32; 4]> {
        let mut profile = [0.0f32; 4];
        let mut per_side_strokes = 0;
        for stroke in strokes {
            let Some(widths) = stroke.per_side_widths() else {
                continue;
            };
            per_side_strokes += 1;
            for (side, width) in profile.iter_mut().zip(widths) {
                *side = side.max(width);
            }
        }
        (per_side_strokes > 1).then_some(profile)
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
            paint.set_path_effect(self.path_effect());
        }

        paint
    }

    /// On/off run lengths of this stroke's dash pattern; `None` for solid and
    /// dotted. The defaults are width-derived, so each side gets its own.
    pub fn dash_pattern(&self) -> Option<Vec<f32>> {
        match self.style {
            StrokeStyle::Dashed => {
                let dash = self.dash.unwrap_or(self.width + 10.);
                let gap = self.gap.unwrap_or(self.width + 10.);
                Some(vec![dash, gap])
            }
            StrokeStyle::Mixed => Some(vec![
                self.width + 5.,
                self.width + 5.,
                self.width + 1.,
                self.width + 5.,
            ]),
            _ => None,
        }
    }

    /// Circle radius and centre-to-centre spacing of a dotted stroke; `None`
    /// for every other style.
    pub fn dot_pattern(&self) -> Option<(f32, f32)> {
        if self.style != StrokeStyle::Dotted {
            return None;
        }
        let radius = match self.kind {
            StrokeKind::Inner | StrokeKind::Outer => self.width,
            StrokeKind::Center => self.width / 2.0,
        };
        Some((radius, self.width + 5.0))
    }

    /// The dash/dot `PathEffect` for this stroke's style.
    pub fn path_effect(&self) -> Option<skia::PathEffect> {
        match self.style {
            StrokeStyle::Solid => None,
            StrokeStyle::Dotted => {
                let (radius, advance) = self.dot_pattern()?;
                Self::dot_effect(radius, advance)
            }
            _ => skia::PathEffect::dash(&self.dash_pattern()?, 0.),
        }
    }

    /// The dash/dot `PathEffect` stretched so a run of `length` begins and ends
    /// on a dash, as a browser fits a border. Runs too short come out solid.
    pub fn path_effect_fitted(&self, length: f32) -> Option<skia::PathEffect> {
        if length <= 0.0 {
            return self.path_effect();
        }
        match self.style {
            StrokeStyle::Solid => None,
            StrokeStyle::Dotted => {
                let (radius, _) = self.dot_pattern()?;
                Self::dot_effect(radius, self.fitted_dot_advance(length)?)
            }
            _ => skia::PathEffect::dash(&self.fitted_dash_pattern(length)?, 0.),
        }
    }

    /// The dash pattern scaled so `length` holds a whole number of dashes and
    /// both ends land on one, instead of being cut wherever the run stops.
    pub fn fitted_dash_pattern(&self, length: f32) -> Option<Vec<f32>> {
        let pattern = self.dash_pattern()?;
        let period: f32 = pattern.iter().sum();
        let first = pattern[0];
        if length <= 0.0 || period <= 0.0 || first <= 0.0 {
            return Some(pattern);
        }
        // Whole periods that fit before the closing dash.
        let repeats = ((length - first) / period).round().max(0.0);
        let scale = length / (repeats * period + first);
        Some(pattern.iter().map(|run| run * scale).collect())
    }

    /// Dot spacing scaled so a dot lands on both ends of a `length` run.
    pub fn fitted_dot_advance(&self, length: f32) -> Option<f32> {
        let (_, advance) = self.dot_pattern()?;
        if length <= 0.0 || advance <= 0.0 {
            return Some(advance);
        }
        Some(length / (length / advance).round().max(1.0))
    }

    fn dot_effect(radius: f32, advance: f32) -> Option<skia::PathEffect> {
        let circle_path = {
            let mut pb = skia::PathBuilder::new();
            pb.add_circle((0.0, 0.0), radius, None);
            pb.detach()
        };
        skia::PathEffect::path_1d(
            &circle_path,
            advance,
            0.0,
            skia::path_1d_path_effect::Style::Translate,
        )
    }

    /// The same stroke narrowed to a single side's width, so that side's
    /// geometry and width-derived dash pattern come out of the shared style.
    pub fn with_width(&self, width: f32) -> Stroke {
        Stroke {
            width,
            widths: None,
            ..self.clone()
        }
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
        if self.style != StrokeStyle::Solid {
            return None;
        }
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
    fn per_side_profile_needs_two_strokes_to_miter_against() {
        let one = [stroke_with_widths(Some([10.0, 0.0, 0.0, 0.0]))];
        assert_eq!(Stroke::per_side_profile(one.iter()), None);

        // A uniform stroke has no side of its own, so it never contributes.
        let uniform = [
            stroke_with_widths(Some([10.0, 0.0, 0.0, 0.0])),
            stroke_with_widths(Some([4.0, 4.0, 4.0, 4.0])),
            stroke_with_widths(None),
        ];
        assert_eq!(Stroke::per_side_profile(uniform.iter()), None);
    }

    #[test]
    fn per_side_profile_takes_the_widest_side_of_each() {
        let strokes = [
            stroke_with_widths(Some([10.0, 0.0, 0.0, 0.0])),
            stroke_with_widths(Some([0.0, 20.0, 0.0, 0.0])),
            stroke_with_widths(Some([0.0, 0.0, 15.0, 5.0])),
            stroke_with_widths(Some([6.0, 0.0, 0.0, 0.0])),
        ];
        assert_eq!(
            Stroke::per_side_profile(strokes.iter()),
            Some([10.0, 20.0, 15.0, 5.0])
        );
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

    #[test]
    fn with_width_keeps_style_and_drops_per_side_widths() {
        let mut stroke = stroke_with_widths(Some([1.0, 2.0, 3.0, 4.0]));
        stroke.style = StrokeStyle::Dashed;
        stroke.dash = Some(7.0);
        let side = stroke.with_width(3.0);
        assert_eq!(side.width, 3.0);
        assert_eq!(side.widths, None);
        assert_eq!(side.style, StrokeStyle::Dashed);
        assert_eq!(side.dash, Some(7.0));
        assert_eq!(side.kind, stroke.kind);
    }

    #[test]
    fn dash_pattern_follows_side_width() {
        let mut stroke = solid_center(4.0);
        assert_eq!(stroke.dash_pattern(), None);

        stroke.style = StrokeStyle::Dashed;
        // Defaults are width-relative, so each side gets its own pattern.
        assert_eq!(stroke.dash_pattern(), Some(vec![14.0, 14.0]));
        assert_eq!(
            stroke.with_width(10.0).dash_pattern(),
            Some(vec![20.0, 20.0])
        );

        // An explicit dash/gap is shared by every side.
        stroke.dash = Some(6.0);
        stroke.gap = Some(2.0);
        assert_eq!(stroke.dash_pattern(), Some(vec![6.0, 2.0]));
        assert_eq!(stroke.with_width(10.0).dash_pattern(), Some(vec![6.0, 2.0]));

        stroke.style = StrokeStyle::Mixed;
        assert_eq!(stroke.dash_pattern(), Some(vec![9.0, 9.0, 5.0, 9.0]));
    }

    #[test]
    fn fitted_dash_pattern_ends_on_a_dash() {
        let mut stroke = solid_center(10.0);
        stroke.style = StrokeStyle::Dashed;
        stroke.dash = Some(30.0);
        stroke.gap = Some(30.0);

        // 235 holds 4 dashes and 3 gaps once stretched; both ends are a dash.
        let fitted = stroke.fitted_dash_pattern(235.0).unwrap();
        assert!((4.0 * fitted[0] + 3.0 * fitted[1] - 235.0).abs() < 1e-3);
        // 175 only fits 3, so the sides stretch by different amounts.
        let fitted = stroke.fitted_dash_pattern(175.0).unwrap();
        assert!((3.0 * fitted[0] + 2.0 * fitted[1] - 175.0).abs() < 1e-3);

        // A side too short for a whole period becomes one dash, as in CSS.
        let fitted = stroke.fitted_dash_pattern(40.0).unwrap();
        assert!((fitted[0] - 40.0).abs() < 1e-3);

        // Mixed keeps its four-run shape and still closes on its first run.
        stroke.style = StrokeStyle::Mixed;
        stroke.dash = None;
        stroke.gap = None;
        let fitted = stroke.fitted_dash_pattern(200.0).unwrap();
        let period: f32 = fitted.iter().sum();
        let repeats = ((200.0 - fitted[0]) / period).round();
        assert!((repeats * period + fitted[0] - 200.0).abs() < 1e-3);
    }

    #[test]
    fn fitted_dot_advance_lands_on_both_ends() {
        let mut stroke = solid_center(8.0);
        stroke.style = StrokeStyle::Dotted;
        // Nominal advance is 13; 200 / 13 rounds to 15 steps of 13.33.
        let advance = stroke.fitted_dot_advance(200.0).unwrap();
        assert!((200.0 / advance - 15.0).abs() < 1e-3);
        // Shorter than one advance still yields a single step, never zero.
        assert!(stroke.fitted_dot_advance(4.0).unwrap() > 0.0);
    }

    #[test]
    fn dot_pattern_halves_the_radius_only_when_centered() {
        let mut stroke = solid_center(8.0);
        assert_eq!(stroke.dot_pattern(), None);

        stroke.style = StrokeStyle::Dotted;
        assert_eq!(stroke.dot_pattern(), Some((4.0, 13.0)));
        stroke.kind = StrokeKind::Inner;
        assert_eq!(stroke.dot_pattern(), Some((8.0, 13.0)));
        assert_eq!(stroke.with_width(2.0).dot_pattern(), Some((2.0, 7.0)));
    }

    #[test]
    fn path_effect_is_none_only_for_solid() {
        let mut stroke = solid_center(4.0);
        assert!(stroke.path_effect().is_none());
        for style in [StrokeStyle::Dashed, StrokeStyle::Dotted, StrokeStyle::Mixed] {
            stroke.style = style;
            assert!(stroke.path_effect().is_some(), "{style:?} needs an effect");
        }
    }

    fn solid_center(width: f32) -> Stroke {
        Stroke::new_center_stroke(width, StrokeStyle::Solid, None, None, None, None)
    }

    #[test]
    fn inner_outer_simplify_to_center_when_thin() {
        let inner = Stroke::new_inner_stroke(8.0, StrokeStyle::Solid, None, None, None, None);
        // 2 * 8 * 0.1 = 1.6 < 2.0, simplify to Center
        assert_eq!(
            inner.simplified_kind_at_scale(false, 0.1),
            StrokeKind::Center
        );
        // 2 * 8 * 0.2 = 3.2 >= 2.0, keep Inner
        assert_eq!(
            inner.simplified_kind_at_scale(false, 0.2),
            StrokeKind::Inner
        );
    }

    #[test]
    fn dash_becomes_solid_when_period_subpixel() {
        let dashed =
            Stroke::new_center_stroke(2.0, StrokeStyle::Dashed, None, None, Some(20.0), Some(20.0));
        // period 20 * 0.03 = 0.6 < 0.75, solid
        assert_eq!(dashed.style_at_scale(0.03), StrokeStyle::Solid);
        // period 20 * 0.05 = 1.0 >= 0.75, keep dashed
        assert_eq!(dashed.style_at_scale(0.05), StrokeStyle::Dashed);
    }

    #[test]
    fn native_linecap_is_used_for_solid_strokes() {
        let solid = Stroke::new_center_stroke(
            4.0,
            StrokeStyle::Solid,
            Some(StrokeCap::Round),
            Some(StrokeCap::Round),
            None,
            None,
        );
        assert_eq!(solid.to_skia_linecap(), Some(skia::paint::Cap::Round));
    }

    #[test]
    fn native_linecap_is_skipped_for_dashed_strokes() {
        let dashed = Stroke::new_center_stroke(
            4.0,
            StrokeStyle::Dashed,
            Some(StrokeCap::Round),
            Some(StrokeCap::Round),
            None,
            None,
        );
        assert_eq!(dashed.to_skia_linecap(), None);
    }

    #[test]
    fn path_lod_never_drops_thin_stroke() {
        // Hairline strokes must still paint (stroke-only icons).
        let thin = solid_center(1.0).path_lod_at_scale(false, 0.5);
        assert_eq!(thin.width, 1.0);
        assert_eq!(thin.kind, StrokeKind::Center);
    }

    #[test]
    fn path_lod_simplifies_inner_at_overview() {
        let inner = Stroke::new_inner_stroke(8.0, StrokeStyle::Solid, None, None, None, None);
        let lod = inner.path_lod_at_scale(false, 0.1);
        assert_eq!(lod.kind, StrokeKind::Center);
    }
}
