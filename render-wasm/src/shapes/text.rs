use crate::render::text::decoration_segments;
use crate::{
    math::{Bounds, Matrix, Rect},
    render::{default_font, DEFAULT_EMOJI_FONT},
    utils::Browser,
};

use core::f32;
use macros::ToJs;
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
use skia_safe::{
    self as skia,
    paint::{self, Paint},
    textlayout::Affinity,
    textlayout::ParagraphBuilder,
    textlayout::ParagraphStyle,
    textlayout::PlaceholderAlignment,
    textlayout::PlaceholderStyle,
    textlayout::PositionWithAffinity,
    textlayout::TextBaseline,
    Contains,
};

use std::borrow::Cow;
use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;
use unicode_segmentation::UnicodeSegmentation;

use super::text_horizontal::*;
use super::text_japanese::*;
use super::{FontFamily, FontStyle};
use crate::math::Point;
use crate::shapes::{self, kinsoku, merge_fills, Shape, Type, VerticalAlign};
use crate::utils::{get_fallback_fonts, get_font_collection};
use crate::Uuid;

// TODO: maybe move this to the wasm module?
pub type ParagraphBuilderGroup = Vec<ParagraphBuilder>;

/// True when the modifier changes the text layout container (resize), as opposed
/// to rotation/move where glyph layout can be reused.
pub fn modifier_changes_text_layout(base: &Shape, modifier: &Matrix) -> bool {
    let Type::Text(text_content) = &base.shape_type else {
        return false;
    };
    let before = oriented_container_bounds(base);
    let after = before.transform(modifier);
    match text_content.grow_type() {
        GrowType::AutoWidth => !crate::math::is_close_to(before.height(), after.height()),
        GrowType::AutoHeight if text_content.is_vertical() => {
            !crate::math::is_close_to(before.height(), after.height())
        }
        GrowType::AutoHeight => !crate::math::is_close_to(before.width(), after.width()),
        GrowType::Fixed => {
            !crate::math::is_close_to(before.width(), after.width())
                || !crate::math::is_close_to(before.height(), after.height())
        }
    }
}

fn oriented_container_bounds(shape: &Shape) -> Bounds {
    let selrect = shape.selrect();
    let mut bounds = Bounds::new(
        Point::new(selrect.x(), selrect.y()),
        Point::new(selrect.x() + selrect.width(), selrect.y()),
        Point::new(
            selrect.x() + selrect.width(),
            selrect.y() + selrect.height(),
        ),
        Point::new(selrect.x(), selrect.y() + selrect.height()),
    );
    if !shape.transform.is_identity() {
        let mut matrix = shape.transform;
        let center = shape.center();
        matrix.post_translate(center);
        matrix.pre_translate(-center);
        bounds.transform_mut(&matrix);
    }
    bounds
}

#[repr(u8)]
#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
pub enum GrowType {
    Fixed = 0,
    AutoWidth = 1,
    AutoHeight = 2,
}

#[derive(Debug, PartialEq, Copy, Clone)]
pub struct TextContentSize {
    pub width: f32,
    pub height: f32,
    pub max_width: f32,
    pub normalized_line_height: f32,
}

const DEFAULT_TEXT_CONTENT_SIZE: f32 = 0.01;

/// Matches `marginRight: "1px"` on `.paragraph-set` in the HTML text renderer
/// (`frontend/src/app/main/ui/shapes/text/styles.cljs`). DOM `getBoundingClientRect`
/// includes that margin in auto-width measurements; Skia `longest_line()` does not.
const PARAGRAPH_SET_MARGIN_RIGHT: f32 = 1.0;

impl TextContentSize {
    pub fn default() -> Self {
        Self {
            width: DEFAULT_TEXT_CONTENT_SIZE,
            height: DEFAULT_TEXT_CONTENT_SIZE,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height: 0.0,
        }
    }

    pub fn new_with_size(width: f32, height: f32) -> Self {
        Self {
            width,
            height,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height: 0.0,
        }
    }

    pub fn new_with_normalized_line_height(
        width: f32,
        height: f32,
        max_width: f32,
        normalized_line_height: f32,
    ) -> Self {
        Self {
            width,
            height,
            max_width,
            normalized_line_height,
        }
    }

    pub fn set_size(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
    }

    pub fn copy_finite_size(
        &mut self,
        size: TextContentSize,
        default_height: f32,
        default_width: f32,
    ) {
        if f32::is_finite(size.width) {
            self.width = size.width;
        } else {
            self.width = default_width;
        }
        if f32::is_finite(size.max_width) {
            self.max_width = size.max_width;
        } else {
            self.max_width = default_width
        }
        if f32::is_finite(size.height) {
            self.height = size.height;
        } else {
            self.height = default_height;
        }
        if f32::is_finite(size.normalized_line_height) {
            self.normalized_line_height = size.normalized_line_height;
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct TextPositionWithAffinity {
    pub position_with_affinity: PositionWithAffinity,
    pub paragraph: usize,
    pub offset: usize,
}

impl PartialEq for TextPositionWithAffinity {
    fn eq(&self, other: &Self) -> bool {
        self.paragraph == other.paragraph && self.offset == other.offset
    }
}

impl TextPositionWithAffinity {
    pub fn new(
        position_with_affinity: PositionWithAffinity,
        paragraph: usize,
        offset: usize,
    ) -> Self {
        Self {
            position_with_affinity,
            paragraph,
            offset,
        }
    }

    pub fn empty() -> Self {
        Self {
            position_with_affinity: PositionWithAffinity {
                position: 0,
                affinity: Affinity::Downstream,
            },
            paragraph: 0,
            offset: 0,
        }
    }

    pub fn new_downstream_affinity(paragraph: usize, offset: usize) -> Self {
        Self {
            position_with_affinity: PositionWithAffinity {
                position: offset as i32,
                affinity: Affinity::Downstream,
            },
            paragraph,
            offset,
        }
    }

    pub fn new_upstream_affinity(paragraph: usize, offset: usize) -> Self {
        Self {
            position_with_affinity: PositionWithAffinity {
                position: offset as i32,
                affinity: Affinity::Upstream,
            },
            paragraph,
            offset,
        }
    }

    pub fn reset(&mut self) {
        self.position_with_affinity.position = 0;
        self.position_with_affinity.affinity = Affinity::Downstream;
        self.paragraph = 0;
        self.offset = 0;
    }
}

#[derive(Debug)]
pub struct TextContentLayoutResult(
    Vec<ParagraphBuilderGroup>,
    Vec<Vec<skia::textlayout::Paragraph>>,
    TextContentSize,
);

/// Cached extrect stored as offsets from the selrect origin,
/// keyed by the selrect dimensions (width, height) and vertical alignment
/// used to compute it.
#[derive(Debug, Clone, Copy)]
struct CachedExtrect {
    selrect_width: f32,
    selrect_height: f32,
    valign: u8,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
}

/// Vertical layout with the content version and box it was laid out for.
#[derive(Clone)]
struct VerticalLayoutCache {
    version: u64,
    rect: Rect,
    layout: Rc<super::text_vertical::VerticalLayout>,
}

impl std::fmt::Debug for VerticalLayoutCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VerticalLayoutCache")
            .field("version", &self.version)
            .field("rect", &self.rect)
            .finish_non_exhaustive()
    }
}

/// Horizontal paragraph plans with the content version they were built for.
#[derive(Clone)]
struct HorizontalPlansCache {
    version: u64,
    plans: Rc<Vec<HorizontalParagraphPlan>>,
}

/// Line adjustment of one horizontal Japanese paragraph at the layout width,
/// per span: the sheds that squeeze its lines and the letter-spacing that
/// justifies them.
#[derive(Debug, Clone, Default)]
pub(crate) struct HorizontalAdjustment {
    sheds: Vec<Vec<(usize, f32)>>,
    spacing: Vec<Vec<(usize, f32)>>,
}

/// Per paragraph; `None` for paragraphs laid out by Skia alone.
type HorizontalAdjustments = Rc<Vec<Option<HorizontalAdjustment>>>;

#[derive(Debug, Clone)]
struct LineAdjustmentCache {
    version: u64,
    width: f32,
    adjustments: HorizontalAdjustments,
}

impl std::fmt::Debug for HorizontalPlansCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HorizontalPlansCache")
            .field("version", &self.version)
            .finish_non_exhaustive()
    }
}

#[derive(Debug)]
pub struct TextContentLayout {
    pub paragraph_builders: Vec<ParagraphBuilderGroup>,
    /// Shared across shape clones (e.g. modifier transforms) so rotation/pan
    /// can paint without rebuilding Skia layout. Cleared builders on clone are OK.
    pub paragraphs: Rc<Vec<Vec<skia::textlayout::Paragraph>>>,
    cached_extrect: Cell<Option<CachedExtrect>>,
    /// Last vertical layout, shared across clones like `paragraphs`.
    vertical: RefCell<Option<VerticalLayoutCache>>,
    /// Last horizontal paragraph plans, shared across clones.
    horizontal: RefCell<Option<HorizontalPlansCache>>,
    /// Last Japanese line adjustment, shared across clones.
    line_adjustment: RefCell<Option<LineAdjustmentCache>>,
}

impl Default for TextContentLayout {
    fn default() -> Self {
        Self::new()
    }
}

impl Clone for TextContentLayout {
    fn clone(&self) -> Self {
        Self {
            paragraph_builders: vec![],
            paragraphs: Rc::clone(&self.paragraphs),
            cached_extrect: Cell::new(self.cached_extrect.get()),
            vertical: RefCell::new(self.vertical.borrow().clone()),
            horizontal: RefCell::new(self.horizontal.borrow().clone()),
            line_adjustment: RefCell::new(self.line_adjustment.borrow().clone()),
        }
    }
}

impl PartialEq for TextContentLayout {
    fn eq(&self, _other: &Self) -> bool {
        true
    }
}

impl TextContentLayout {
    pub fn new() -> Self {
        Self {
            paragraph_builders: vec![],
            paragraphs: Rc::new(Vec::new()),
            cached_extrect: Cell::new(None),
            vertical: RefCell::new(None),
            horizontal: RefCell::new(None),
            line_adjustment: RefCell::new(None),
        }
    }

    pub fn set(
        &mut self,
        paragraph_builders: Vec<ParagraphBuilderGroup>,
        paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
    ) {
        self.paragraph_builders = paragraph_builders;
        self.paragraphs = Rc::new(paragraphs);
        self.cached_extrect.set(None);
    }

    pub fn clear(&mut self) {
        self.paragraph_builders.clear();
        self.paragraphs = Rc::new(Vec::new());
        self.cached_extrect.set(None);
    }

    pub fn needs_update(&self) -> bool {
        self.paragraphs.is_empty()
    }
}

#[derive(Debug, Clone)]
pub struct TextDecorationSegment {
    #[allow(dead_code)]
    pub kind: skia::textlayout::TextDecoration,
    pub text_style: skia::textlayout::TextStyle,
    pub y: f32,
    pub thickness: f32,
    pub left: f32,
    pub width: f32,
}

impl TextDecorationSegment {
    /// The bar to paint, centered on `y`.
    pub fn rect(&self) -> Rect {
        Rect::new(
            self.left,
            self.y - self.thickness / 2.0,
            self.left + self.width,
            self.y + self.thickness / 2.0,
        )
    }
}

pub fn vertical_align_offset(container_h: f32, content_h: f32, valign: VerticalAlign) -> f32 {
    match valign {
        VerticalAlign::Center => (container_h - content_h) / 2.0,
        VerticalAlign::Bottom => container_h - content_h,
        _ => 0.0,
    }
}

fn intersects(paragraph: &skia_safe::textlayout::Paragraph, x: f32, y: f32) -> bool {
    if y < 0.0 || y > paragraph.height() {
        return false;
    }

    let pos = paragraph.get_glyph_position_at_coordinate((x, y));
    let idx = pos.position as usize;

    let rects =
        paragraph.get_rects_for_range(0..idx + 1, RectHeightStyle::Tight, RectWidthStyle::Tight);

    rects.iter().any(|r| r.rect.contains(&Point::new(x, y)))
}

fn paragraph_intersects<'a>(
    paragraphs: impl Iterator<Item = &'a skia::textlayout::Paragraph>,
    x_pos: f32,
    y_pos: f32,
) -> bool {
    paragraphs
        .scan(0.0_f32, |height, p| {
            let prev_height = *height;
            *height += p.height();
            Some((prev_height, p))
        })
        .any(|(height, p)| intersects(p, x_pos, y_pos - height))
}

// Performs a text auto layout without width limits.
// This should be the same as text_auto_layout.
pub fn build_paragraphs_from_paragraph_builders(
    paragraph_builders: &mut [ParagraphBuilderGroup],
    width: f32,
) -> Vec<Vec<skia::textlayout::Paragraph>> {
    let paragraphs = paragraph_builders
        .iter_mut()
        .map(|builders| {
            builders
                .iter_mut()
                .map(|builder| {
                    let mut paragraph = builder.build();
                    // For auto-width, always layout with infinite width first to get intrinsic width
                    paragraph.layout(width);
                    paragraph
                })
                .collect()
        })
        .collect();
    paragraphs
}

/// Calculate the normalized line height from paragraph builders
pub fn calculate_normalized_line_height(
    paragraph_builders: &mut [ParagraphBuilderGroup],
    width: f32,
) -> f32 {
    let mut normalized_line_height = 0.0;
    for paragraph_builder_group in paragraph_builders.iter_mut() {
        for paragraph_builder in paragraph_builder_group.iter_mut() {
            let mut paragraph = paragraph_builder.build();
            paragraph.layout(width);
            let baseline = paragraph.ideographic_baseline();
            if baseline > normalized_line_height {
                normalized_line_height = baseline;
            }
        }
    }
    normalized_line_height
}

#[derive(Debug, Clone)]
pub struct TextContent {
    pub paragraphs: Vec<Paragraph>,
    pub bounds: Rect,
    pub grow_type: GrowType,
    /// How over-long lines are fitted, for the whole shape.
    line_adjustment: LineAdjustment,
    pub size: TextContentSize,
    pub layout: TextContentLayout,
    content_version: u64,
    layout_version: u64,
    layout_width: Option<f32>,
    layout_height: Option<f32>,
    /// Canvas origin used when absolute fill shaders (image/gradient) were baked
    /// into cached Skia paragraphs. Kept across move clones so paint can
    /// translate glyphs + shaders together. See `cached_layout_paint_offset`.
    layout_paint_origin: Option<Point>,
}

impl PartialEq for TextContent {
    fn eq(&self, other: &Self) -> bool {
        self.paragraphs == other.paragraphs
            && self.bounds == other.bounds
            && self.grow_type == other.grow_type
            && self.size == other.size
            && self.layout == other.layout
    }
}

impl TextContent {
    pub fn new(bounds: Rect, grow_type: GrowType) -> Self {
        Self {
            paragraphs: Vec::new(),
            bounds,
            grow_type,
            line_adjustment: LineAdjustment::default(),
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
            layout_height: None,
            layout_paint_origin: None,
        }
    }

    pub fn new_bounds(&self, bounds: Rect) -> Self {
        let paragraphs = self.paragraphs.clone();
        let grow_type = self.grow_type;
        Self {
            paragraphs,
            bounds,
            grow_type,
            line_adjustment: self.line_adjustment,
            size: TextContentSize::new_with_size(bounds.width(), bounds.height()),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
            layout_height: None,
            layout_paint_origin: None,
        }
    }

    pub fn bounds(&self) -> Rect {
        self.bounds
    }

    /// Anchor used when painting from the layout cache. Absolute image/gradient
    /// shaders were built in this coordinate space; paint glyphs here and apply
    /// [`cached_layout_paint_offset`] on the canvas so both move together.
    pub fn cached_layout_paint_anchor(&self, selrect: &Rect) -> Point {
        self.layout_paint_origin
            .unwrap_or_else(|| Point::new(selrect.x(), selrect.y()))
    }

    /// Canvas translation from the baked paint origin to the current selrect.
    /// Zero when there is no recorded origin (fall back to painting at selrect).
    pub fn cached_layout_paint_offset(&self, selrect: &Rect) -> Point {
        match self.layout_paint_origin {
            Some(origin) => Point::new(selrect.x() - origin.x, selrect.y() - origin.y),
            None => Point::new(0.0, 0.0),
        }
    }

    /// Text content for paint when [`Rect`] size may differ from stored bounds
    /// (e.g. modifier transform). Reuses `self` when width/height match; otherwise
    /// clones paragraphs into a rebound copy with an empty layout cache.
    pub fn paint_content_for_selrect<'a>(&'a self, selrect: Rect) -> Cow<'a, Self> {
        let stored_bounds = self.bounds();
        if (stored_bounds.width() - selrect.width()).abs() < 0.01
            && (stored_bounds.height() - selrect.height()).abs() < 0.01
        {
            Cow::Borrowed(self)
        } else {
            Cow::Owned(self.new_bounds(selrect))
        }
    }

    pub fn set_xywh(&mut self, x: f32, y: f32, w: f32, h: f32) {
        self.bounds = Rect::from_xywh(x, y, w, h);
    }

    /// Distinct font families referenced by this content's spans, in first-seen
    /// order.
    pub fn font_families(&self) -> Vec<FontFamily> {
        let mut seen: Vec<FontFamily> = Vec::new();
        for paragraph in &self.paragraphs {
            for span in paragraph.children() {
                if !seen.contains(&span.font_family) {
                    seen.push(span.font_family);
                }
            }
        }
        seen
    }

    pub fn add_paragraph(&mut self, mut paragraph: Paragraph) {
        let index = self.paragraphs.len() as u32;
        paragraph.set_span_positions(index);
        self.paragraphs.push(paragraph);
        self.content_version = self.content_version.wrapping_add(1);
    }

    pub fn reset_span_positions(&mut self) {
        for (index, paragraph) in self.paragraphs.iter_mut().enumerate() {
            paragraph.set_span_positions(index as u32);
        }
    }

    pub fn paragraphs(&self) -> &[Paragraph] {
        &self.paragraphs
    }

    /// True when some span holds an emoji: an emoji code point, or an emoji
    /// presentation selector or keycap mark (`©️`, `#️⃣`). Color emoji need an
    /// overlay above strokes.
    pub fn has_emoji(&self) -> bool {
        self.paragraphs
            .iter()
            .flat_map(|p| p.children())
            .flat_map(|span| span.text.chars())
            .any(|c| super::text_vertical::is_emoji_char(c) || matches!(c, '\u{FE0F}' | '\u{20E3}'))
    }

    pub fn paragraphs_mut(&mut self) -> &mut Vec<Paragraph> {
        self.content_version = self.content_version.wrapping_add(1);
        &mut self.paragraphs
    }

    pub fn width(&self) -> f32 {
        self.size.width
    }

    pub fn normalized_line_height(&self) -> f32 {
        self.size.normalized_line_height
    }

    /// Vertical layout for the box `rect`: its height is the column-wrap
    /// budget and span paints resolve against it. Cached until the content or
    /// the box changes; clones share it.
    pub fn vertical_layout(&self, rect: &Rect) -> Rc<super::text_vertical::VerticalLayout> {
        if let Some(entry) = self.layout.vertical.borrow().as_ref() {
            if entry.version == self.content_version && entry.rect == *rect {
                return Rc::clone(&entry.layout);
            }
        }
        let layout = Rc::new(super::text_vertical::layout_for_rect(self, rect));
        *self.layout.vertical.borrow_mut() = Some(VerticalLayoutCache {
            version: self.content_version,
            rect: *rect,
            layout: Rc::clone(&layout),
        });
        layout
    }

    /// True when a horizontal paragraph gets its lines adjusted here rather
    /// than by Skia: Japanese text that squeezes or is justified.
    fn adjusts_lines(&self, paragraph: &Paragraph, plan: &HorizontalParagraphPlan) -> bool {
        uses_japanese_layout(paragraph, &plan.texts)
            && (self.line_adjustment == LineAdjustment::PushInFirst
                || paragraph.text_align() == skia::textlayout::TextAlign::Justify)
    }

    /// Squeeze sheds and justify spacing of each horizontal Japanese
    /// paragraph at the layout width (see `horizontal_squeeze_sheds` and
    /// `horizontal_justify_spacing`), cached until the content or the width
    /// changes. `None` when no paragraph needs them.
    fn horizontal_line_adjustments(&self) -> Option<HorizontalAdjustments> {
        if self.is_vertical() || self.grow_type() == GrowType::AutoWidth {
            return None;
        }
        let plans = self.horizontal_plans();
        let adjusted: Vec<bool> = self
            .paragraphs
            .iter()
            .zip(plans.iter())
            .map(|(paragraph, plan)| self.adjusts_lines(paragraph, plan))
            .collect();
        if !adjusted.contains(&true) {
            return None;
        }
        let width = self.get_width(self.bounds().width());
        if let Some(entry) = self.layout.line_adjustment.borrow().as_ref() {
            if entry.version == self.content_version && entry.width == width {
                return Some(Rc::clone(&entry.adjustments));
            }
        }
        let adjustments: HorizontalAdjustments = Rc::new(
            self.paragraphs
                .iter()
                .zip(plans.iter())
                .zip(&adjusted)
                .map(|((paragraph, plan), adjusted)| {
                    adjusted.then(|| self.adjust_horizontal_lines(paragraph, plan, width))
                })
                .collect(),
        );
        *self.layout.line_adjustment.borrow_mut() = Some(LineAdjustmentCache {
            version: self.content_version,
            width,
            adjustments: Rc::clone(&adjustments),
        });
        Some(adjustments)
    }

    fn adjust_horizontal_lines(
        &self,
        paragraph: &Paragraph,
        plan: &HorizontalParagraphPlan,
        width: f32,
    ) -> HorizontalAdjustment {
        let lay_out = |extra_sheds: &[Vec<(usize, f32)>]| {
            let mut laid_out = self
                .horizontal_measure_builder(paragraph, plan, extra_sheds)
                .build();
            laid_out.layout(width);
            laid_out
        };
        let (sheds, laid_out) = if self.line_adjustment == LineAdjustment::PushInFirst {
            horizontal_squeeze_sheds(paragraph, plan, width, &lay_out)
        } else {
            let sheds = vec![Vec::new(); plan.texts.len()];
            let laid_out = lay_out(&sheds);
            (sheds, laid_out)
        };
        let spacing = if paragraph.text_align() == skia::textlayout::TextAlign::Justify {
            horizontal_justify_spacing(&laid_out, plan, width)
        } else {
            vec![Vec::new(); plan.texts.len()]
        };
        HorizontalAdjustment { sheds, spacing }
    }

    /// Left-aligned builder of one paragraph with plain fills, its plan's
    /// sheds plus `extra_sheds`, for measuring line adjustment.
    fn horizontal_measure_builder(
        &self,
        paragraph: &Paragraph,
        plan: &HorizontalParagraphPlan,
        extra_sheds: &[Vec<(usize, f32)>],
    ) -> ParagraphBuilder {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_style = paragraph.paragraph_to_style();
        paragraph_style.set_apply_rounding_hack(false);
        paragraph_style.set_text_align(skia::textlayout::TextAlign::Left);
        let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
        for (span_index, ((span, text), sheds)) in paragraph
            .children()
            .iter()
            .zip(&plan.texts)
            .zip(&plan.sheds)
            .enumerate()
        {
            let combined: Vec<(usize, f32)> = sheds
                .iter()
                .chain(extra_sheds.get(span_index).into_iter().flatten())
                .copied()
                .collect();
            let text_style = span.to_style_with_paint(
                &self.bounds(),
                fallback_fonts,
                false,
                paragraph.line_height(),
                None,
            );
            builder.push_style(&text_style);
            add_horizontal_span(
                &mut builder,
                span,
                text,
                &combined,
                &plan.ruby_spacing.adjustments[span_index],
                &text_style,
                fonts,
            );
        }
        builder
    }

    /// Horizontal passes of every paragraph (kinsoku texts, offsets, aki
    /// sheds, ruby spacing), cached until the content changes; clones share
    /// them. A font load bumps the content version, which ruby spacing needs.
    pub(crate) fn horizontal_plans(&self) -> Rc<Vec<HorizontalParagraphPlan>> {
        if let Some(entry) = self.layout.horizontal.borrow().as_ref() {
            if entry.version == self.content_version {
                return Rc::clone(&entry.plans);
            }
        }
        let plans: Rc<Vec<HorizontalParagraphPlan>> = Rc::new(
            self.paragraphs
                .iter()
                .map(HorizontalParagraphPlan::new)
                .collect(),
        );
        *self.layout.horizontal.borrow_mut() = Some(HorizontalPlansCache {
            version: self.content_version,
            plans: Rc::clone(&plans),
        });
        plans
    }

    /// Writing mode applies to the whole shape: the first paragraph sets it.
    pub fn is_vertical(&self) -> bool {
        self.paragraphs
            .first()
            .is_some_and(|p| p.writing_mode() == WritingMode::VerticalRl)
    }

    /// Vertical writing paints from its own layout, not the cached
    /// horizontal paragraphs. Horizontal ruby, warichu and emphasis marks
    /// paint from the cached paragraphs like the base text.
    pub fn can_paint_from_layout_cache(&self) -> bool {
        !self.is_vertical()
    }

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }

    pub fn line_adjustment(&self) -> LineAdjustment {
        self.line_adjustment
    }

    pub fn set_line_adjustment(&mut self, line_adjustment: LineAdjustment) {
        if self.line_adjustment != line_adjustment {
            self.line_adjustment = line_adjustment;
            self.content_version = self.content_version.wrapping_add(1);
        }
    }

    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        if self.grow_type != grow_type {
            self.grow_type = grow_type;
            self.content_version = self.content_version.wrapping_add(1);
        }
    }

    /// Compute a tight text rect from laid-out Skia paragraphs using glyph
    /// metrics (fm.top for overshoot, line descent for bottom, line left/width
    /// for horizontal extent).
    fn rect_from_paragraphs(&self, selrect: &Rect, valign: VerticalAlign) -> Option<Rect> {
        let paragraphs = &self.layout.paragraphs;
        let x = selrect.x();
        let base_y = selrect.y();

        let total_height: f32 = paragraphs
            .iter()
            .filter_map(|group| group.first())
            .map(|p| p.height())
            .sum();

        let vertical_offset = vertical_align_offset(selrect.height(), total_height, valign);

        let mut min_x = f32::MAX;
        let mut min_y = f32::MAX;
        let mut max_x = f32::MIN;
        let mut max_y = f32::MIN;
        let mut has_lines = false;
        let mut y_accum = base_y + vertical_offset;

        for group in paragraphs.iter() {
            if let Some(paragraph) = group.first() {
                let line_metrics = paragraph.get_line_metrics();
                for line in &line_metrics {
                    let line_baseline = y_accum + line.baseline as f32;

                    // Use per-glyph fm.top for tighter vertical bounds when
                    // available; fall back to line-level ascent for empty lines
                    // (where get_style_metrics returns nothing).
                    let style_metrics = line.get_style_metrics(line.start_index..line.end_index);
                    if style_metrics.is_empty() {
                        min_y = min_y.min(line_baseline - line.ascent as f32);
                    } else {
                        for (_start, style_metric) in &style_metrics {
                            let fm = &style_metric.font_metrics;
                            min_y = min_y.min(line_baseline + fm.top);
                        }
                    }

                    // Bottom uses line-level descent (includes descender space
                    // for the whole line, not just present glyphs).
                    max_y = max_y.max(line_baseline + line.descent as f32);
                    min_x = min_x.min(x + line.left as f32);
                    max_x = max_x.max(x + line.left as f32 + line.width as f32);
                    has_lines = true;
                }
                y_accum += paragraph.height();
            }
        }

        if has_lines {
            Some(Rect::from_ltrb(min_x, min_y, max_x, max_y))
        } else {
            None
        }
    }

    fn compute_and_cache_extrect(
        &self,
        shape: &Shape,
        selrect: &Rect,
        valign: VerticalAlign,
    ) -> Rect {
        // AutoWidth paragraphs are laid out with f32::MAX, so line metrics
        // (line.left) reflect alignment within that huge width and are
        // unusable for tight bounds.  Fall back to content_rect.
        // Vertical writing takes its bounds from content_rect; the
        // skparagraph line metrics below describe an unused horizontal layout.
        if self.is_vertical() {
            return self.vertical_extrect(selrect, valign);
        }
        if self.grow_type() == GrowType::AutoWidth {
            return self.content_rect(selrect, valign);
        }

        let layout_matches_container = self
            .layout_width
            .is_some_and(|w| w.ceil() == self.get_width(selrect.width()).ceil());

        let tight = if !self.layout.paragraphs.is_empty() && layout_matches_container {
            self.rect_from_paragraphs(selrect, valign)
        } else {
            let mut text_content = self.clone();
            text_content.update_layout(shape.selrect);
            text_content.rect_from_paragraphs(selrect, valign)
        }
        .unwrap_or_else(|| self.content_rect(selrect, valign));

        // Cache as offsets from selrect origin so it's position-independent.
        let sx = selrect.x();
        let sy = selrect.y();
        self.layout.cached_extrect.set(Some(CachedExtrect {
            selrect_width: selrect.width(),
            selrect_height: selrect.height(),
            valign: valign as u8,
            left: tight.left() - sx,
            top: tight.top() - sy,
            right: tight.right() - sx,
            bottom: tight.bottom() - sy,
        }));

        tight
    }

    pub fn calculate_bounds(&self, shape: &Shape, apply_transform: bool) -> Bounds {
        let transform = &shape.transform;
        let center = &shape.center();
        let selrect = shape.selrect();
        let valign = shape.vertical_align();
        let sw = selrect.width();
        let sh = selrect.height();
        let sx = selrect.x();
        let sy = selrect.y();

        // Try the cache first: if dimensions and valign match, just apply position offset.
        let text_rect = if let Some(cached) = self.layout.cached_extrect.get() {
            if (cached.selrect_width - sw).abs() < 0.1
                && (cached.selrect_height - sh).abs() < 0.1
                && cached.valign == valign as u8
            {
                Rect::from_ltrb(
                    sx + cached.left,
                    sy + cached.top,
                    sx + cached.right,
                    sy + cached.bottom,
                )
            } else {
                self.compute_and_cache_extrect(shape, &selrect, valign)
            }
        } else {
            self.compute_and_cache_extrect(shape, &selrect, valign)
        };

        let mut bounds = Bounds::new(
            Point::new(text_rect.x(), text_rect.y()),
            Point::new(text_rect.x() + text_rect.width(), text_rect.y()),
            Point::new(
                text_rect.x() + text_rect.width(),
                text_rect.y() + text_rect.height(),
            ),
            Point::new(text_rect.x(), text_rect.y() + text_rect.height()),
        );

        if apply_transform && !transform.is_identity() {
            let mut matrix = *transform;
            matrix.post_translate(*center);
            matrix.pre_translate(-*center);
            bounds.transform_mut(&matrix);
        }

        bounds
    }

    /// Content rect of vertical text grown to the laid-out column block, which
    /// overflows a fixed shape that is too narrow for its columns.
    fn vertical_extrect(&self, selrect: &Rect, valign: VerticalAlign) -> Rect {
        let mut rect = self.content_rect(selrect, valign);
        let layout = self.vertical_layout(selrect);
        let left = selrect.left()
            + super::text_vertical::block_axis_offset(selrect.width(), layout.width, valign);
        rect.join(Rect::from_xywh(
            left,
            selrect.top(),
            layout.width,
            layout.height,
        ));
        rect
    }

    pub fn content_rect(&self, selrect: &Rect, valign: VerticalAlign) -> Rect {
        // Auto-grow bounds follow the same block anchor as the vertical paint.
        if self.is_vertical() {
            let (width, height) = match self.grow_type() {
                GrowType::AutoWidth => (self.size.width, self.size.height),
                GrowType::AutoHeight => (self.size.width, selrect.height()),
                GrowType::Fixed => (selrect.width(), selrect.height()),
            };
            let x = selrect.x()
                + super::text_vertical::block_axis_offset(selrect.width(), width, valign);
            return Rect::from_xywh(x, selrect.y(), width, height);
        }

        let x = selrect.x();
        let mut y = selrect.y();

        let width = if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            selrect.width()
        };

        let height = if self.size.width.round() != width.round() {
            self.get_height(width)
        } else {
            self.size.height
        };

        let offset_y = vertical_align_offset(selrect.height(), height, valign);
        y += offset_y;

        Rect::from_xywh(x, y, width, height)
    }

    pub fn transform(&mut self, transform: &Matrix) {
        let left = self.bounds.left();
        let right = self.bounds.right();
        let top = self.bounds.top();
        let bottom = self.bounds.bottom();
        let p1 = transform.map_point(skia::Point::new(left, top));
        let p2 = transform.map_point(skia::Point::new(right, bottom));
        self.bounds = Rect::from_ltrb(p1.x, p1.y, p2.x, p2.y);
    }

    /// Caret position for a selrect-local point. Vertical text wraps its
    /// columns at the `selrect` height, as painting does; stored bounds can
    /// be taller than a fixed shape.
    pub fn get_caret_position_from_shape_coords(
        &self,
        point: &Point,
        selrect: &Rect,
        vertical_align: VerticalAlign,
    ) -> Option<TextPositionWithAffinity> {
        // Vertical writing: resolve through the vertical pass. The content
        // block is right-anchored.
        if self.is_vertical() {
            let layout = self.vertical_layout(selrect);
            let cx = point.x
                - super::text_vertical::block_axis_offset(
                    selrect.width(),
                    layout.width,
                    vertical_align,
                );
            let (paragraph, offset) = super::text_vertical::caret_from_point(&layout, cx, point.y)?;
            return Some(TextPositionWithAffinity::new_downstream_affinity(
                paragraph, offset,
            ));
        }

        let mut offset_y = 0.0;
        let layout_paragraphs = self.layout.paragraphs.iter().flatten();

        for (paragraph_index, layout_paragraph) in layout_paragraphs.enumerate() {
            let start_y = offset_y;
            let end_y = offset_y + layout_paragraph.height();

            // We only test against paragraphs that can contain the current y
            // coordinate. Use >= for start and handle zero-height paragraphs.
            let paragraph_height = layout_paragraph.height();
            let matches = if paragraph_height > 0.0 {
                point.y >= start_y && point.y < end_y
            } else {
                // For zero-height paragraphs (empty lines), match if we're at the start position
                point.y >= start_y && point.y <= start_y + 1.0
            };

            if matches {
                // Skia's get_glyph_position_at_coordinate expects coordinates relative to
                // the paragraph's top-left. For multi-paragraph or wrapped text, each
                // paragraph has its own origin; subtract start_y so we pass paragraph-local coords.
                let para_pt = Point::new(point.x, point.y - start_y);
                if let Some(paragraph) = self.paragraphs().get(paragraph_index) {
                    if let Some(original_position) =
                        horizontal_warichu_hit_test(paragraph, layout_paragraph, para_pt)
                    {
                        return Some(TextPositionWithAffinity::new_downstream_affinity(
                            paragraph_index,
                            original_position,
                        ));
                    }
                }
                let position_with_affinity =
                    layout_paragraph.get_glyph_position_at_coordinate((para_pt.x, para_pt.y));
                if let Some(plan) = self.horizontal_plans().get(paragraph_index) {
                    // Skia reports builder-text UTF-16 offsets (transformed and
                    // kinsoku-shifted); the model counts source characters.
                    let offset = plan
                        .offsets
                        .builder_to_source(position_with_affinity.position as usize);

                    return Some(TextPositionWithAffinity::new(
                        position_with_affinity,
                        paragraph_index,
                        offset,
                    ));
                }
            }
            offset_y += layout_paragraph.height();
        }

        // Handle completely empty text shapes: if there are no paragraphs or all paragraphs
        // are empty, and the click is within the text shape bounds, return a default position
        if (self.paragraphs().is_empty() || self.layout.paragraphs.is_empty())
            && self.bounds.contains(*point)
        {
            // Create a default position at the start of the text
            use skia_safe::textlayout::Affinity;
            let default_position = PositionWithAffinity {
                position: 0,
                affinity: Affinity::Downstream,
            };
            return Some(TextPositionWithAffinity::new(
                default_position,
                0, // paragraph 0
                0, // offset 0
            ));
        }

        None
    }

    pub fn get_caret_position_from_screen_coords(
        &self,
        point: &Point,
        view_matrix: &Matrix,
        shape_matrix: &Matrix,
        selrect: &Rect,
        vertical_align: VerticalAlign,
    ) -> Option<TextPositionWithAffinity> {
        let shape_rel_point = Shape::get_relative_point(point, view_matrix, shape_matrix)?;
        self.get_caret_position_from_shape_coords(&shape_rel_point, selrect, vertical_align)
    }

    /// Builds the ParagraphBuilders necessary to render
    /// this text.
    pub fn paragraph_builder_group_from_text(
        &self,
        use_shadow: Option<bool>,
    ) -> Vec<ParagraphBuilderGroup> {
        self.paragraph_builders(use_shadow, false, None, None, None, None)
    }

    /// Creates paragraph builders with always-opaque paint (BLACK @ alpha 255).
    /// Used as a clip mask for inner stroke rendering.
    pub fn paragraph_builder_group_opaque(&self) -> Vec<ParagraphBuilderGroup> {
        self.paragraph_builders(None, true, None, None, None, None)
    }

    /// Maximum number of stacked fills across every span in this text block.
    pub fn max_fill_layers(&self) -> usize {
        self.paragraphs()
            .iter()
            .flat_map(|p| p.children())
            .map(|s| s.fills.len())
            .max()
            .unwrap_or(0)
    }

    /// Builds paragraph builders that paint a single fill layer per span for SVG
    /// export. `layer_from_bottom` is 0 for the bottommost fill (fills[last]).
    pub fn paragraph_builder_group_for_fill_layer(
        &self,
        layer_from_bottom: usize,
    ) -> Vec<ParagraphBuilderGroup> {
        self.paragraph_builders(None, false, None, Some(layer_from_bottom), None, None)
    }

    /// Like [`paragraph_builder_group_for_fill_layer`], but spans whose fill at
    /// this layer is an image in `skip_image_ids` get transparent paint (those
    /// fills are re-emitted as linked SVG `<image>` elements).
    pub fn paragraph_builder_group_for_fill_layer_skipping_images(
        &self,
        layer_from_bottom: usize,
        skip_image_ids: &HashSet<Uuid>,
    ) -> Vec<ParagraphBuilderGroup> {
        self.paragraph_builders(
            None,
            false,
            None,
            Some(layer_from_bottom),
            None,
            Some(skip_image_ids),
        )
    }

    /// Opaque black glyphs only for spans whose fill at `layer_from_bottom` is
    /// the given image — used as an SVG `<clipPath>` for linked image fills.
    pub fn paragraph_builder_group_opaque_for_image_layer(
        &self,
        layer_from_bottom: usize,
        image_id: Uuid,
    ) -> Vec<ParagraphBuilderGroup> {
        self.paragraph_builders(
            None,
            false,
            None,
            None,
            Some((layer_from_bottom, image_id)),
            None,
        )
    }

    fn paragraph_builders(
        &self,
        use_shadow: Option<bool>,
        opaque: bool,
        align_override: Option<skia::textlayout::TextAlign>,
        fill_layer: Option<usize>,
        opaque_image_layer: Option<(usize, Uuid)>,
        skip_image_ids: Option<&HashSet<Uuid>>,
    ) -> Vec<ParagraphBuilderGroup> {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_group = Vec::new();
        let plans = self.horizontal_plans();
        let adjustments = align_override
            .is_none()
            .then(|| self.horizontal_line_adjustments())
            .flatten();

        for (paragraph_index, (paragraph, plan)) in
            self.paragraphs().iter().zip(plans.iter()).enumerate()
        {
            let adjustment = adjustments
                .as_ref()
                .and_then(|adjustments| adjustments.get(paragraph_index))
                .and_then(Option::as_ref);
            let mut paragraph_style = paragraph.paragraph_to_style();
            if self.adjusts_lines(paragraph, plan) {
                // Line adjustment gives characters runs of their own; rounding
                // each run up would wrap the adjusted lines. The measuring
                // pass shares this style so both break alike.
                paragraph_style.set_apply_rounding_hack(false);
            }
            if let Some(align) = align_override {
                paragraph_style.set_text_align(align);
            } else if adjustment.is_some()
                && paragraph.text_align() == skia::textlayout::TextAlign::Justify
            {
                // The justify spacing already fills the lines.
                paragraph_style.set_text_align(skia::textlayout::TextAlign::Left);
            }
            let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
            let mut has_text = false;
            for (span_index, (((span, text), plan_sheds), ruby_extra)) in paragraph
                .children()
                .iter()
                .zip(&plan.texts)
                .zip(&plan.sheds)
                .zip(&plan.ruby_spacing.adjustments)
                .enumerate()
            {
                let squeeze = adjustment.and_then(|adjustment| adjustment.sheds.get(span_index));
                let justify = adjustment.and_then(|adjustment| adjustment.spacing.get(span_index));
                let combined_sheds: Vec<(usize, f32)>;
                let sheds: &[(usize, f32)] = match squeeze {
                    Some(squeeze) if !squeeze.is_empty() => {
                        combined_sheds = plan_sheds.iter().chain(squeeze).copied().collect();
                        &combined_sheds
                    }
                    _ => plan_sheds,
                };
                let combined: Vec<(usize, f32)>;
                let extra: &[(usize, f32)] = match justify {
                    Some(justify) if !justify.is_empty() => {
                        combined = ruby_extra.iter().chain(justify).copied().collect();
                        &combined
                    }
                    _ => ruby_extra,
                };
                let text_style = if let Some((layer, image_id)) = opaque_image_layer {
                    let mut style = span.to_style(
                        &self.bounds(),
                        fallback_fonts,
                        false,
                        paragraph.line_height(),
                    );
                    let mut paint = paint::Paint::default();
                    match span.fills_from_bottom(layer) {
                        Some(shapes::Fill::Image(img)) if img.id() == image_id => {
                            paint.set_color(skia::Color::BLACK);
                            paint.set_alpha(255);
                        }
                        _ => {
                            paint.set_color(skia::Color::TRANSPARENT);
                        }
                    }
                    style.set_foreground_paint(&paint);
                    style
                } else if let (Some(layer), Some(skip)) = (fill_layer, skip_image_ids) {
                    let skip_span = matches!(
                        span.fills_from_bottom(layer),
                        Some(shapes::Fill::Image(img)) if skip.contains(&img.id())
                    );
                    if skip_span {
                        let mut style = span.to_style(
                            &self.bounds(),
                            fallback_fonts,
                            false,
                            paragraph.line_height(),
                        );
                        let mut paint = paint::Paint::default();
                        paint.set_color(skia::Color::TRANSPARENT);
                        style.set_foreground_paint(&paint);
                        style
                    } else {
                        let remove_alpha =
                            opaque || (use_shadow.unwrap_or(false) && !span.is_transparent());
                        span.to_style_with_paint(
                            &self.bounds(),
                            fallback_fonts,
                            remove_alpha,
                            paragraph.line_height(),
                            fill_layer,
                        )
                    }
                } else {
                    let remove_alpha =
                        opaque || (use_shadow.unwrap_or(false) && !span.is_transparent());
                    span.to_style_with_paint(
                        &self.bounds(),
                        fallback_fonts,
                        remove_alpha,
                        paragraph.line_height(),
                        fill_layer,
                    )
                };
                if !text.is_empty() {
                    has_text = true;
                }
                builder.push_style(&text_style);
                add_horizontal_span(&mut builder, span, text, sheds, extra, &text_style, fonts);
            }
            if !has_text {
                builder.add_text(" ");
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    /// Performs an Auto Width text layout.
    fn text_layout_auto_width(&self) -> TextContentLayoutResult {
        // Left-aligned MAX-width pass: longest_line() is glyph width, not the huge container.
        let mut measure_builders = self.paragraph_builders(
            None,
            false,
            Some(skia::textlayout::TextAlign::Left),
            None,
            None,
            None,
        );

        let normalized_line_height =
            calculate_normalized_line_height(&mut measure_builders, f32::MAX);

        let measure_paragraphs =
            build_paragraphs_from_paragraph_builders(&mut measure_builders, f32::MAX);

        let content_width = measure_paragraphs
            .iter()
            .flatten()
            .fold(0.0_f32, |auto_width, paragraph| {
                f32::max(paragraph.longest_line(), auto_width)
            })
            .ceil();

        // Re-layout at the intrinsic width (without the HTML margin slack).
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs =
            build_paragraphs_from_paragraph_builders(&mut paragraph_builders, content_width);
        let height = paragraphs
            .iter()
            .flatten()
            .fold(0.0_f32, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });

        let reported_width = content_width + PARAGRAPH_SET_MARGIN_RIGHT;
        let size = TextContentSize::new_with_normalized_line_height(
            reported_width,
            height.ceil(),
            reported_width,
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Private function that performs
    /// Performs an Auto Height text layout.
    fn text_layout_auto_height(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });
        let size = TextContentSize::new_with_normalized_line_height(
            width,
            height.ceil(),
            DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Performs a Fixed text layout.
    fn text_layout_fixed(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let paragraph_height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });

        let size = TextContentSize::new_with_normalized_line_height(
            width.ceil(),
            paragraph_height.ceil(),
            DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    pub fn get_width(&self, width: f32) -> f32 {
        if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            width
        }
    }

    pub fn get_height(&self, width: f32) -> f32 {
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let paragraph_height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });
        paragraph_height
    }

    pub fn needs_update_layout(&self) -> bool {
        self.layout.needs_update()
    }

    /// True when cached Skia paragraphs can be painted as-is (no rebuild/layout).
    pub fn has_usable_paint_layout(&self, shape: &Shape) -> bool {
        if self.layout.needs_update() || self.layout_version != self.content_version {
            return false;
        }
        self.layout_matches_paint_container(shape)
    }

    pub(crate) fn layout_cache_versions_match(&self) -> bool {
        !self.layout.needs_update() && self.layout_version == self.content_version
    }

    pub(crate) fn layout_matches_paint_container(&self, shape: &Shape) -> bool {
        if self.grow_type() == GrowType::AutoWidth {
            return true;
        }
        let Some(layout_w) = self.layout_width else {
            return false;
        };
        let container_w = self.get_width(shape.selrect().width());
        (layout_w - container_w).abs() < f32::EPSILON
    }

    /// True when any span requests underline/overline/line-through (custom draw path).
    pub fn has_text_decorations(&self) -> bool {
        self.paragraphs().iter().any(|paragraph| {
            paragraph.children().iter().any(|span| {
                matches!(
                    span.text_decoration,
                    Some(d) if d != skia::textlayout::TextDecoration::NO_DECORATION
                )
            })
        })
    }

    pub fn set_layout_from_result(
        &mut self,
        result: TextContentLayoutResult,
        default_width: f32,
        default_height: f32,
    ) {
        self.layout.set(result.0, result.1);
        self.size
            .copy_finite_size(result.2, default_width, default_height);
        // Paragraph paints (incl. absolute image/gradient shaders) were built
        // against `self.bounds()` in `paragraph_builder_group_from_text`.
        self.layout_paint_origin = Some(Point::new(self.bounds.x(), self.bounds.y()));
    }

    pub fn force_next_layout_update(&mut self) {
        self.layout_width = None;
        self.layout_height = None;
        self.layout_paint_origin = None;
        self.layout.cached_extrect.set(None);
        // Bump the content version so update_layout can't early-return: auto-width
        // shapes always match their container and clearing the cache above doesn't
        // flip needs_update(), so a late font resolution would otherwise be skipped.
        self.content_version = self.content_version.wrapping_add(1);
    }

    pub fn update_layout(&mut self, selrect: Rect) -> TextContentSize {
        // Keep bounds in sync before building paints so absolute fill shaders
        // match the container we are laying out for.
        self.set_xywh(selrect.x(), selrect.y(), selrect.width(), selrect.height());

        // Vertical columns wrap by height, horizontal lines by width.
        let width_matches = self
            .layout_width
            .is_some_and(|w| (w - selrect.width()).abs() < f32::EPSILON);
        let height_matches = self
            .layout_height
            .is_some_and(|h| (h - selrect.height()).abs() < f32::EPSILON);
        let layout_matches_container = match self.grow_type() {
            GrowType::AutoWidth => true,
            GrowType::AutoHeight if self.is_vertical() => height_matches,
            GrowType::AutoHeight => width_matches,
            GrowType::Fixed => width_matches && height_matches,
        };

        if !self.layout.needs_update()
            && self.layout_version == self.content_version
            && layout_matches_container
        {
            return self.size;
        }

        self.size.set_size(selrect.width(), selrect.height());
        self.layout_height = Some(selrect.height());

        if self.is_vertical() {
            // Vertical writing takes sizes from the vertical pass, so the
            // horizontal skparagraph layout is skipped. Empty paragraph slots
            // mark the layout as done. Auto-width fits both axes without
            // wrapping. Auto-height wraps at the shape height and grows width
            // as columns advance right-to-left. Fixed keeps both dimensions.
            self.layout.set(
                Vec::new(),
                (0..self.paragraphs.len().max(1))
                    .map(|_| Vec::new())
                    .collect(),
            );
            self.layout_width = Some(selrect.width());
            match self.grow_type() {
                GrowType::AutoWidth => {
                    let layout = self.vertical_layout(&selrect);
                    self.size.width = layout.width.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.height = layout.height.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.max_width = self.size.width;
                }
                GrowType::AutoHeight => {
                    let layout = self.vertical_layout(&selrect);
                    self.size.width = layout.width.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.height = selrect.height();
                    self.size.max_width = self.size.width;
                }
                GrowType::Fixed => {}
            }
        } else {
            match self.grow_type() {
                GrowType::AutoHeight => {
                    let result = self.text_layout_auto_height();
                    self.layout_width = Some(result.2.width);
                    self.set_layout_from_result(result, selrect.width(), selrect.height());
                }
                GrowType::AutoWidth => {
                    let result = self.text_layout_auto_width();
                    self.layout_width = Some(result.2.width);
                    self.set_layout_from_result(result, selrect.width(), selrect.height());
                }
                GrowType::Fixed => {
                    let result = self.text_layout_fixed();
                    self.layout_width = Some(result.2.width);
                    self.set_layout_from_result(result, selrect.width(), selrect.height());
                }
            }
        }

        if self.is_empty() && self.grow_type() != GrowType::Fixed {
            let (placeholder_width, placeholder_height) = self.placeholder_dimensions(selrect);
            if self.grow_type() == GrowType::AutoWidth || self.is_vertical() {
                self.size.width = placeholder_width;
                self.size.max_width = placeholder_width;
            }
            if self.grow_type() == GrowType::AutoWidth || !self.is_vertical() {
                self.size.height = placeholder_height;
            }
        }

        self.layout_version = self.content_version;
        self.size
    }

    /// Return true when the content represents a freshly created empty text.
    /// We consider it empty only if there is exactly one paragraph with a single
    /// span whose text buffer is empty. Any additional paragraphs or characters
    /// mean the user has already entered content.
    fn is_empty(&self) -> bool {
        if self.paragraphs.len() != 1 {
            return false;
        }

        let paragraph = match self.paragraphs.first() {
            Some(paragraph) => paragraph,
            None => return true,
        };
        if paragraph.children().len() != 1 {
            return false;
        }

        let span = match paragraph.children().first() {
            Some(span) => span,
            None => return true,
        };

        span.text.is_empty()
    }

    /// Compute the placeholder size used while the text is still empty. We ask
    /// Skia to measure a single glyph using the span's typography so the editor
    /// shows a caret-sized box that reflects the selected font, size and spacing.
    /// If that fails we fall back to the previous WASM size or the incoming
    /// selrect dimensions.
    fn placeholder_dimensions(&self, selrect: Rect) -> (f32, f32) {
        if self.is_vertical() {
            let layout = self.vertical_layout(&selrect);
            let font_size = self
                .paragraphs
                .first()
                .and_then(|p| p.children().first())
                .map_or(14.0, |s| s.font_size);
            return (
                layout.width.ceil().max(DEFAULT_TEXT_CONTENT_SIZE),
                font_size,
            );
        }
        if let Some(paragraph) = self.paragraphs.first() {
            if let Some(span) = paragraph.children().first() {
                let fonts = get_font_collection();
                let fallback_fonts = get_fallback_fonts();
                let paragraph_style = paragraph.paragraph_to_style();
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);

                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    false,
                    paragraph.line_height(),
                );

                builder.push_style(&text_style);
                builder.add_text("0");

                let mut paragraph_layout = builder.build();
                paragraph_layout.layout(f32::MAX);

                let width = paragraph_layout.max_intrinsic_width();
                let height = paragraph_layout.height();

                return (width, height);
            }
        }

        let fallback_width = selrect.width().max(self.size.width);
        let fallback_height = selrect.height().max(self.size.height);

        (fallback_width, fallback_height)
    }

    #[allow(dead_code)]
    pub fn intersect_position_in_shape(&self, shape: &Shape, x_pos: f32, y_pos: f32) -> bool {
        let rect = shape.selrect;
        let mut matrix = Matrix::new_identity();
        let center = shape.center();
        let Some(inv_transform) = &shape.transform.invert() else {
            return false;
        };
        matrix.pre_translate(center);
        matrix.pre_concat(inv_transform);
        matrix.pre_translate(-center);

        let result = matrix.map_point((x_pos, y_pos));

        let x_pos = result.x;
        let y_pos = result.y;

        x_pos >= rect.x() && x_pos <= rect.right() && y_pos >= rect.y() && y_pos <= rect.bottom()
    }

    pub fn intersect_position_in_text(&self, shape: &Shape, x_pos: f32, y_pos: f32) -> bool {
        let rect = self.content_rect(&shape.selrect, shape.vertical_align);
        let mut matrix = Matrix::new_identity();
        let center = shape.center();
        let Some(inv_transform) = &shape.transform.invert() else {
            return false;
        };
        matrix.pre_translate(center);
        matrix.pre_concat(inv_transform);
        matrix.pre_translate(-center);

        let result = matrix.map_point((x_pos, y_pos));

        // Vertical writing: hit-test against the laid-out cells (absolute
        // coordinates, right-anchored to the selrect).
        if self.is_vertical() {
            let layout = self.vertical_layout(&shape.selrect);
            return super::text_vertical::intersects(
                &layout,
                &shape.selrect,
                shape.vertical_align(),
                result.x,
                result.y,
            );
        }

        // Change coords to content space
        let x_pos = result.x - rect.x();
        let y_pos = result.y - rect.y();

        if !self.layout.paragraphs.is_empty() {
            // Reuse stored laid-out paragraphs
            paragraph_intersects(
                self.layout
                    .paragraphs
                    .iter()
                    .flat_map(|group| group.first()),
                x_pos,
                y_pos,
            )
        } else {
            let width = self.width();
            let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
            let paragraphs =
                build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);

            paragraph_intersects(paragraphs.iter().flatten(), x_pos, y_pos)
        }
    }
}

impl Default for TextContent {
    fn default() -> Self {
        Self {
            paragraphs: vec![],
            bounds: Rect::default(),
            grow_type: GrowType::Fixed,
            line_adjustment: LineAdjustment::default(),
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
            layout_height: None,
            layout_paint_origin: None,
        }
    }
}

pub type TextAlign = skia::textlayout::TextAlign;
pub type TextDirection = skia::textlayout::TextDirection;
pub type TextDecoration = skia::textlayout::TextDecoration;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum TextTransform {
    Lowercase,
    Uppercase,
    Capitalize,
}

// FIXME: Rethink this type. We'll probably need to move the serialization to the
// wasm module and store here meaningful model values (and/or skia type aliases)
#[derive(Debug, PartialEq, Clone)]
pub struct Paragraph {
    text_align: TextAlign,
    text_direction: TextDirection,
    text_decoration: Option<TextDecoration>,
    text_transform: Option<TextTransform>,
    writing_mode: WritingMode,
    text_orientation: TextOrientation,
    line_height: f32,
    letter_spacing: f32,
    children: Vec<TextSpan>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self {
            text_align: TextAlign::default(),
            text_direction: TextDirection::LTR,
            text_decoration: None,
            text_transform: None,
            writing_mode: WritingMode::default(),
            text_orientation: TextOrientation::default(),
            line_height: 1.0,
            letter_spacing: 0.0,
            children: vec![],
        }
    }
}

impl Paragraph {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text_align: TextAlign,
        text_direction: TextDirection,
        text_decoration: Option<TextDecoration>,
        text_transform: Option<TextTransform>,
        line_height: f32,
        letter_spacing: f32,
        children: Vec<TextSpan>,
    ) -> Self {
        Self {
            text_align,
            text_direction,
            text_decoration,
            text_transform,
            writing_mode: WritingMode::default(),
            text_orientation: TextOrientation::default(),
            line_height,
            letter_spacing,
            children,
        }
    }

    pub fn writing_mode(&self) -> WritingMode {
        self.writing_mode
    }

    pub fn set_writing_mode(&mut self, writing_mode: WritingMode) {
        self.writing_mode = writing_mode;
    }

    pub fn text_orientation(&self) -> TextOrientation {
        self.text_orientation
    }

    pub fn set_text_orientation(&mut self, text_orientation: TextOrientation) {
        self.text_orientation = text_orientation;
    }

    pub fn children(&self) -> &[TextSpan] {
        &self.children
    }

    pub fn children_mut(&mut self) -> &mut Vec<TextSpan> {
        &mut self.children
    }

    fn set_span_positions(&mut self, index: u32) {
        for (span_index, span) in self.children.iter_mut().enumerate() {
            span.set_position(index, span_index as u32);
        }
    }

    /// Translate a character offset into the UTF-16 offset Skia indexes by.
    /// Both differ on astral-plane characters (emoji) and whenever a text
    /// transform changes the length of the laid out text (`ß` -> `SS`).
    pub fn char_offset_to_utf16(&self, char_offset: usize) -> usize {
        let mut remaining = char_offset;
        let mut utf16 = 0;
        for span in &self.children {
            if remaining == 0 {
                break;
            }
            let span_len = span.text.chars().count();
            let take = remaining.min(span_len);
            let prefix: String = span.text.chars().take(take).collect();
            utf16 += span.transform_text(&prefix).encode_utf16().count();
            remaining -= take;
        }
        utf16
    }

    fn text(&self) -> String {
        self.children
            .iter()
            .map(|span| span.text.as_str())
            .collect()
    }

    /// Char offset of the grapheme boundary before `char_offset`, so emojis
    /// made of several chars are one step.
    pub fn prev_grapheme_offset(&self, char_offset: usize) -> usize {
        let mut boundary = 0;
        for grapheme in self.text().graphemes(true) {
            let next = boundary + grapheme.chars().count();
            if next >= char_offset {
                break;
            }
            boundary = next;
        }
        boundary
    }

    /// Char offset of the grapheme boundary after `char_offset`.
    pub fn next_grapheme_offset(&self, char_offset: usize) -> usize {
        let mut boundary = 0;
        for grapheme in self.text().graphemes(true) {
            boundary += grapheme.chars().count();
            if boundary > char_offset {
                break;
            }
        }
        boundary
    }

    pub fn line_height(&self) -> f32 {
        self.line_height
    }

    pub fn letter_spacing(&self) -> f32 {
        self.letter_spacing
    }

    pub fn text_align(&self) -> TextAlign {
        self.text_align
    }

    pub fn text_direction(&self) -> TextDirection {
        self.text_direction
    }

    pub fn text_decoration(&self) -> Option<TextDecoration> {
        self.text_decoration
    }

    pub fn text_transform(&self) -> Option<TextTransform> {
        self.text_transform
    }

    /// Span texts as fed to the paragraph builders (text-transform applied,
    /// Japanese spacing normalized, kinsoku break suppressions inserted),
    /// plus the map from original to builder-text UTF-16 offsets. Consumers
    /// of laid-out offsets must translate through the map. Only paragraphs
    /// with Japanese text or ruby are transformed.
    pub fn layout_span_texts(&self) -> (Vec<String>, kinsoku::OffsetMap) {
        layout_span_texts(self)
    }

    pub fn paragraph_to_style(&self) -> ParagraphStyle {
        let mut style = ParagraphStyle::default();

        style.set_height(self.line_height);
        style.set_text_align(self.text_align);
        style.set_text_direction(self.text_direction);
        style.set_replace_tab_characters(false);
        style.set_apply_rounding_hack(true);
        style.set_text_height_behavior(skia::textlayout::TextHeightBehavior::All);
        style
    }

    pub fn scale_content(&mut self, value: f32) {
        self.letter_spacing *= value;
        self.children
            .iter_mut()
            .for_each(|l| l.scale_content(value));
    }
}

/// Add `text`, pushing every '\t' as a one em wide placeholder.
pub fn add_text_with_tabs(builder: &mut ParagraphBuilder, text: &str, font_size: f32) {
    let tab = PlaceholderStyle::new(
        font_size,
        0.0,
        PlaceholderAlignment::Baseline,
        TextBaseline::Alphabetic,
        0.0,
    );

    for (index, segment) in text.split('\t').enumerate() {
        if index > 0 {
            builder.add_placeholder(&tab);
        }
        builder.add_text(segment);
    }
}

/// Text after browser filtering and CSS text-transform, plus the source
/// UTF-16 range behind each output scalar. One source scalar can yield
/// several (`ß` uppercases to `SS`); the ranges let vertical layout wrap and
/// export those glyphs as one source unit.
#[derive(Debug, Clone, PartialEq)]
pub struct AppliedTextTransform {
    pub text: String,
    pub(crate) source_ranges: Vec<(std::ops::Range<usize>, std::ops::Range<usize>)>,
}

impl AppliedTextTransform {
    pub fn source_utf16_range(
        &self,
        transformed: std::ops::Range<usize>,
    ) -> std::ops::Range<usize> {
        // Output ranges are contiguous and ascending: skip to the first one
        // that ends past `transformed.start`.
        let first = self
            .source_ranges
            .partition_point(|(output, _)| output.end <= transformed.start);
        let mut ranges = self.source_ranges[first..]
            .iter()
            .take_while(|(output, _)| output.start < transformed.end)
            .filter(|(output, _)| output.end > transformed.start)
            .map(|(_, source)| source.clone());
        let Some(first) = ranges.next() else {
            return 0..0;
        };
        ranges.fold(first, |range, source| {
            range.start.min(source.start)..range.end.max(source.end)
        })
    }
}

fn apply_text_transform_with_source_ranges(
    text: &str,
    browser: u8,
    transform: Option<TextTransform>,
) -> AppliedTextTransform {
    let mut output = String::with_capacity(text.len());
    let mut source_ranges = Vec::new();
    let mut source_utf16 = 0usize;
    let mut output_utf16 = 0usize;
    let mut capitalize_next = true;

    for source_char in text.chars() {
        let source_start = source_utf16;
        source_utf16 += source_char.len_utf16();

        // Control characters below U+0020 are filtered (Firefox drops them,
        // other browsers show a space); tabs and line breaks are kept.
        let processed = if matches!(source_char, '\t' | '\n' | '\r' | '\u{2028}' | '\u{2029}')
            || source_char >= '\u{0020}'
        {
            Some(source_char)
        } else if browser == Browser::Firefox as u8 {
            None
        } else {
            Some(' ')
        };
        let Some(processed) = processed else {
            continue;
        };

        let transformed: String = match transform {
            Some(TextTransform::Uppercase) => processed.to_uppercase().collect(),
            Some(TextTransform::Lowercase) => processed.to_lowercase().collect(),
            Some(TextTransform::Capitalize) if processed.is_alphabetic() && capitalize_next => {
                capitalize_next = false;
                processed.to_uppercase().collect()
            }
            Some(TextTransform::Capitalize) => {
                capitalize_next = !processed.is_alphabetic();
                processed.to_string()
            }
            None => processed.to_string(),
        };

        for transformed_char in transformed.chars() {
            let transformed_start = output_utf16;
            output_utf16 += transformed_char.len_utf16();
            source_ranges.push((transformed_start..output_utf16, source_start..source_utf16));
            output.push(transformed_char);
        }
    }

    AppliedTextTransform {
        text: output,
        source_ranges,
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextSpan {
    pub text: String,
    pub font_family: FontFamily,
    pub font_size: f32,
    pub line_height: f32,
    pub letter_spacing: f32,
    pub font_weight: i32,
    pub font_variant_id: Uuid,
    pub text_decoration: Option<TextDecoration>,
    pub text_transform: Option<TextTransform>,
    pub text_direction: TextDirection,
    pub text_orientation: TextOrientation,
    pub text_combine_upright: TextCombineUpright,
    /// Emphasis mark (圏点 / bouten) applied to each base character.
    pub text_emphasis: TextEmphasis,
    /// Ruby (furigana) annotation for this span; empty means no ruby.
    pub ruby: String,
    pub ruby_size: RubySize,
    pub ruby_align: RubyAlign,
    pub ruby_overhang: RubyOverhang,
    pub ruby_side: RubySide,
    /// Warichu (割注): two half-size lines stacked in one column position.
    pub warichu: bool,
    pub font_features: FontFeatures,
    pub annotation_clearance: AnnotationClearance,
    pub fills: Vec<shapes::Fill>,
    pub paragraph_position: u32,
    pub span_position: u32,
}

impl Default for TextSpan {
    fn default() -> Self {
        Self {
            text: String::default(),
            font_family: FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            font_size: 14.0,
            line_height: 1.2,
            letter_spacing: 0.0,
            font_weight: 400,
            font_variant_id: Uuid::nil(),
            text_decoration: None,
            text_transform: None,
            text_direction: TextDirection::LTR,
            text_orientation: TextOrientation::default(),
            text_combine_upright: TextCombineUpright::default(),
            text_emphasis: TextEmphasis::default(),
            ruby: String::default(),
            ruby_size: RubySize::default(),
            ruby_align: RubyAlign::default(),
            ruby_overhang: RubyOverhang::default(),
            ruby_side: RubySide::default(),
            warichu: false,
            font_features: FontFeatures::default(),
            annotation_clearance: AnnotationClearance::default(),
            fills: vec![],
            paragraph_position: u32::MAX,
            span_position: u32::MAX,
        }
    }
}

impl TextSpan {
    /// Fill at `layer` counting from the bottom (`0` = last / bottommost fill).
    pub fn fills_from_bottom(&self, layer: usize) -> Option<&shapes::Fill> {
        if layer < self.fills.len() {
            Some(&self.fills[self.fills.len() - 1 - layer])
        } else {
            None
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        line_height: f32,
        letter_spacing: f32,
        text_decoration: Option<TextDecoration>,
        text_transform: Option<TextTransform>,
        text_direction: TextDirection,
        font_weight: i32,
        font_variant_id: Uuid,
        fills: Vec<shapes::Fill>,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            line_height,
            letter_spacing,
            text_decoration,
            text_transform,
            text_direction,
            font_weight,
            font_variant_id,
            fills,
            ..Self::default()
        }
    }

    pub fn set_text(&mut self, text: String) {
        self.text = text;
    }

    pub fn set_position(&mut self, paragraph: u32, span: u32) {
        self.paragraph_position = paragraph;
        self.span_position = span;
    }

    /// Ruby annotation text, without surrounding whitespace.
    pub fn ruby_text(&self) -> &str {
        self.ruby.trim()
    }

    /// True when the span shows a reading. A warichu span shows none: the
    /// note replaces the base text, so a reading has nothing to annotate.
    pub fn has_ruby(&self) -> bool {
        !self.warichu && !self.ruby_text().is_empty()
    }

    pub fn ruby_font_size(&self) -> f32 {
        self.font_size * self.ruby_size.scale()
    }

    /// Warichu needs at least two characters to fill its two sub-lines.
    pub fn is_warichu(&self) -> bool {
        self.warichu && self.text.chars().count() >= 2
    }

    /// Room, in em of the span, that automatic annotation clearance adds to
    /// the line height: the ruby at its size plus the emphasis marks. `None`
    /// keeps the set line height, so annotations sit in the line gap.
    pub fn annotation_room_em(&self) -> f32 {
        if !self.annotation_clearance.is_auto() {
            return 0.0;
        }
        let ruby = if self.has_ruby() {
            self.ruby_size.scale()
        } else {
            0.0
        };
        let emphasis = if self.text_emphasis.is_none() {
            0.0
        } else {
            super::text_japanese::EMPHASIS_FONT_SCALE
        };
        ruby + emphasis
    }

    /// Automatic clearance stacks emphasis marks outside an over-side ruby.
    pub fn stacks_emphasis_outside_ruby(&self) -> bool {
        self.annotation_clearance.is_auto() && self.has_ruby() && self.ruby_side == RubySide::Over
    }

    /// Cross-axis offset of the emphasis marks past a stacked ruby layer.
    pub fn emphasis_ruby_offset(&self) -> f32 {
        if self.stacks_emphasis_outside_ruby() {
            self.ruby_font_size()
        } else {
            0.0
        }
    }

    pub fn to_style(
        &self,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
    ) -> skia::textlayout::TextStyle {
        self.to_style_with_paint(
            content_bounds,
            fallback_fonts,
            remove_alpha,
            paragraph_line_height,
            None,
        )
    }

    fn to_style_with_paint(
        &self,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
        fill_layer_from_bottom: Option<usize>,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        let paint = if remove_alpha {
            let mut paint = paint::Paint::default();
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
            paint
        } else if let Some(layer) = fill_layer_from_bottom {
            if layer < self.fills.len() {
                let fill_idx = self.fills.len() - 1 - layer;
                self.fills[fill_idx].to_paint(content_bounds, true)
            } else {
                let mut paint = paint::Paint::default();
                paint.set_color(skia::Color::TRANSPARENT);
                paint
            }
        } else {
            merge_fills(&self.fills, *content_bounds)
        };

        let max_line_height =
            f32::max(paragraph_line_height, self.line_height) + self.annotation_room_em();
        style.set_height(max_line_height);
        style.set_height_override(true);
        style.set_foreground_paint(&paint);
        style.set_decoration_type(match self.text_decoration {
            Some(text_decoration) => text_decoration,
            None => skia::textlayout::TextDecoration::NO_DECORATION,
        });

        // Trick to avoid showing the text decoration
        style.set_decoration_thickness_multiplier(0.0);

        let mut font_families = vec![
            self.serialized_font_family(),
            default_font(),
            DEFAULT_EMOJI_FONT.to_string(),
        ];

        font_families.extend(fallback_fonts.iter().cloned());
        style.set_font_families(&font_families);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        match self.font_features {
            FontFeatures::None => {}
            FontFeatures::Palt => style.add_font_feature("palt", 1),
            FontFeatures::Vpal => style.add_font_feature("vpal", 1),
        }
        style.set_half_leading(true);

        style
    }

    pub fn to_stroke_style(
        &self,
        stroke_paint: &Paint,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
    ) -> skia::textlayout::TextStyle {
        let mut style = self.to_style(
            &Rect::default(),
            fallback_fonts,
            remove_alpha,
            paragraph_line_height,
        );
        if remove_alpha {
            let mut paint = skia::Paint::default();
            paint.set_style(stroke_paint.style());
            paint.set_stroke_width(stroke_paint.stroke_width());
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
            style.set_foreground_paint(&paint);
        } else {
            style.set_foreground_paint(stroke_paint);
        }

        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_decoration_type(match self.text_decoration {
            Some(text_decoration) => text_decoration,
            None => skia::textlayout::TextDecoration::NO_DECORATION,
        });
        style
    }

    fn serialized_font_family(&self) -> String {
        format!("{}", self.font_family)
    }

    pub fn transform_text(&self, text: &str) -> String {
        apply_text_transform_with_source_ranges(
            text,
            crate::globals::current_browser(),
            self.text_transform,
        )
        .text
    }

    pub fn apply_text_transform_with_source_ranges(&self) -> AppliedTextTransform {
        apply_text_transform_with_source_ranges(
            &self.text,
            crate::globals::current_browser(),
            self.text_transform,
        )
    }

    pub fn apply_text_transform(&self) -> String {
        self.transform_text(&self.text)
    }

    pub fn scale_content(&mut self, value: f32) {
        self.font_size *= value;
    }

    pub fn is_transparent(&self) -> bool {
        self.fills.iter().all(|fill| match fill {
            shapes::Fill::Solid(shapes::SolidColor(color)) => color.a() == 0,
            _ => false,
        })
    }
}

#[derive(Debug, Copy, Clone)]
pub struct PositionData {
    pub paragraph: u32,
    pub span: u32,
    pub start_pos: u32,
    pub end_pos: u32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub direction: u32,
}

#[derive(Debug)]
pub struct ParagraphLayout {
    pub paragraph: skia::textlayout::Paragraph,
    pub source_paragraph: usize,
    pub x: f32,
    pub y: f32,
    pub decorations: Vec<TextDecorationSegment>,
}

#[derive(Debug)]
pub struct TextLayoutData {
    pub position_data: Vec<PositionData>,
    pub paragraphs: Vec<ParagraphLayout>,
}

pub(crate) fn direction_to_int(direction: TextDirection) -> u32 {
    match direction {
        TextDirection::RTL => 0,
        TextDirection::LTR => 1,
    }
}

pub fn calculate_text_layout_data(
    shape: &Shape,
    text_content: &TextContent,
    paragraph_builder_groups: &mut [ParagraphBuilderGroup],
    skip_position_data: bool,
) -> TextLayoutData {
    let selrect_width = shape.selrect().width();
    let text_width = text_content.get_width(selrect_width);
    let selrect_height = shape.selrect().height();
    let x = shape.selrect.x();
    let base_y = shape.selrect.y();
    let mut position_data: Vec<PositionData> = Vec::new();
    let mut previous_line_height = text_content.normalized_line_height();
    let text_paragraphs = text_content.paragraphs();
    let plans = text_content.horizontal_plans();

    // 1. Build + layout each paragraph once, recording heights as we go.
    let mut paragraph_heights: Vec<f32> = Vec::new();
    let mut built_groups: Vec<Vec<skia::textlayout::Paragraph>> =
        Vec::with_capacity(paragraph_builder_groups.len());
    for paragraph_builder_group in paragraph_builder_groups.iter_mut() {
        let group_len = paragraph_builder_group.len();
        let mut paragraph_offset_y = previous_line_height;
        let mut group_paragraphs: Vec<skia::textlayout::Paragraph> = Vec::with_capacity(group_len);
        for (builder_index, paragraph_builder) in paragraph_builder_group.iter_mut().enumerate() {
            let mut skia_paragraph = paragraph_builder.build();
            skia_paragraph.layout(text_width);
            if builder_index == group_len - 1 {
                if skia_paragraph.get_line_metrics().is_empty() {
                    paragraph_offset_y = skia_paragraph.ideographic_baseline();
                } else {
                    paragraph_offset_y = skia_paragraph.height();
                }
            }
            if builder_index == 0 {
                paragraph_heights.push(skia_paragraph.height());
            }
            group_paragraphs.push(skia_paragraph);
        }
        previous_line_height = paragraph_offset_y;
        built_groups.push(group_paragraphs);
    }

    // 2. Position each built paragraph using the heights from step 1.
    let total_text_height: f32 = paragraph_heights.iter().sum();
    let vertical_offset =
        vertical_align_offset(selrect_height, total_text_height, shape.vertical_align());
    let mut paragraph_layouts: Vec<ParagraphLayout> = Vec::new();
    let mut y_accum = base_y + vertical_offset;
    for (i, group_paragraphs) in built_groups.into_iter().enumerate() {
        // For each paragraph in the group (e.g., fill, stroke, etc.)
        for skia_paragraph in group_paragraphs.into_iter() {
            let decorations = text_paragraphs
                .get(i)
                .zip(plans.get(i))
                .map(|(text_paragraph, plan)| {
                    decoration_segments(&skia_paragraph, text_paragraph, &plan.offsets, x, y_accum)
                })
                .unwrap_or_default();
            paragraph_layouts.push(ParagraphLayout {
                paragraph: skia_paragraph,
                source_paragraph: i,
                x,
                y: y_accum,
                decorations,
            });
        }
        y_accum += paragraph_heights[i];
    }

    // Calculate position data from paragraph_layouts
    if !skip_position_data {
        for para_layout in &paragraph_layouts {
            let paragraph_index = para_layout.source_paragraph;
            if let (Some(text_para), Some(plan)) = (
                text_paragraphs.get(paragraph_index),
                plans.get(paragraph_index),
            ) {
                let entries = HorizontalPositionEntries {
                    paragraph_index,
                    paragraph: text_para,
                    offsets: &plan.offsets,
                    layout: para_layout,
                };
                position_data.extend(entries.collect());
            }
        }
    }

    TextLayoutData {
        position_data,
        paragraphs: paragraph_layouts,
    }
}

/// Position data of one laid-out horizontal paragraph: strips of the spans
/// outside warichu, one strip per warichu sub-line, and one box per
/// emphasis mark.
struct HorizontalPositionEntries<'a> {
    paragraph_index: usize,
    paragraph: &'a Paragraph,
    /// Ranges in builder-text (kinsoku-shifted) space; the map translates
    /// exported positions back to span offsets.
    offsets: &'a HorizontalOffsets,
    layout: &'a ParagraphLayout,
}

impl HorizontalPositionEntries<'_> {
    fn collect(&self) -> Vec<PositionData> {
        let mut entries = Vec::new();
        // Tabs are placeholders too; this keys warichu boxes by span.
        let warichu_rects = super::text_horizontal::horizontal_warichu_placeholders(
            self.paragraph,
            &self.layout.paragraph,
        );
        for range in &self.offsets.ranges {
            if range.warichu {
                let rect = warichu_rects
                    .iter()
                    .find(|(span, _)| *span == range.span)
                    .map(|(_, rect)| *rect);
                if let Some(rect) = rect {
                    entries.extend(self.warichu_entries(range, rect));
                }
            } else {
                entries.extend(self.span_entries(range));
            }
        }
        for placement in
            horizontal_emphasis_placements(self.paragraph, self.offsets, &self.layout.paragraph)
        {
            if let Some(span) = self.paragraph.children().get(placement.span) {
                entries.push(self.entry(
                    placement.span,
                    placement.range.clone(),
                    horizontal_emphasis_mark_box(span, &placement),
                    super::text_vertical::DIRECTION_EMPHASIS_MARK,
                ));
            }
        }
        entries
    }

    /// An entry for `range` of `span`, with `rect` in the laid-out paragraph.
    fn entry(
        &self,
        span: usize,
        range: std::ops::Range<usize>,
        mut rect: Rect,
        direction: u32,
    ) -> PositionData {
        rect.offset((self.layout.x, self.layout.y));
        PositionData {
            paragraph: self.paragraph_index as u32,
            span: span as u32,
            start_pos: range.start as u32,
            end_pos: range.end as u32,
            x: rect.x(),
            y: rect.y(),
            width: rect.width(),
            height: rect.height(),
            direction,
        }
    }

    /// One strip per warichu sub-line: the top half of the placeholder holds
    /// the first line, the bottom half the second.
    fn warichu_entries(&self, range: &HorizontalSpanRange, rect: Rect) -> Vec<PositionData> {
        let Some(span) = self.paragraph.children().get(range.span) else {
            return Vec::new();
        };
        let text = span.apply_text_transform();
        let split = warichu_text_lines(&text).0.encode_utf16().count();
        let end = range.source_end - range.source_start;
        let half = rect.height() / 2.0;
        let ltr = direction_to_int(TextDirection::LTR);
        let top = Rect::from_xywh(rect.x(), rect.y(), rect.width(), half);
        let bottom = Rect::from_xywh(rect.x(), rect.y() + half, rect.width(), half);
        vec![
            self.entry(range.span, 0..split, top, ltr),
            self.entry(range.span, split..end, bottom, ltr),
        ]
    }

    /// One strip per laid-out rect of a span, with its source offsets read
    /// from the glyphs at the rect's edges.
    fn span_entries(&self, range: &HorizontalSpanRange) -> Vec<PositionData> {
        let laid_out = &self.layout.paragraph;
        let to_span_offset = |builder_position: usize| {
            let within = builder_position
                .saturating_sub(range.builder_start)
                .min(range.builder_end - range.builder_start);
            self.offsets
                .offset_map
                .to_original(range.shifted_start + within)
                - range.source_start
        };
        laid_out
            .get_rects_for_range(
                range.builder_start..range.builder_end,
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            )
            .into_iter()
            .map(|textbox| {
                let rect = textbox.rect;
                let cy = rect.top + rect.height() / 2.0;
                let position_at =
                    |x: f32| laid_out.get_glyph_position_at_coordinate((x, cy)).position as usize;
                let start_pos = to_span_offset(position_at(rect.left + 0.1));
                let end_pos = to_span_offset(position_at(rect.right - 0.1));
                self.entry(
                    range.span,
                    start_pos..end_pos,
                    rect,
                    direction_to_int(textbox.direct),
                )
            })
            .collect()
    }
}

pub fn calculate_position_data(
    shape: &Shape,
    text_content: &TextContent,
    skip_position_data: bool,
) -> Vec<PositionData> {
    // Vertical writing generates position data from the vertical cells and
    // needs no horizontal layout.
    if text_content.is_vertical() {
        if skip_position_data {
            return Vec::new();
        }
        let layout = text_content.vertical_layout(&shape.selrect);
        return super::text_vertical::position_data(
            &layout,
            &shape.selrect,
            shape.vertical_align(),
        );
    }

    let mut text_content = text_content.clone();
    text_content.update_layout(shape.selrect);

    let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
    let layout_info = calculate_text_layout_data(
        shape,
        &text_content,
        &mut paragraph_builders,
        skip_position_data,
    );

    layout_info.position_data
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vertical_content(text: &str, grow_type: GrowType) -> TextContent {
        let mut content = TextContent::new(Rect::from_xywh(10.0, 20.0, 100.0, 200.0), grow_type);
        let mut paragraph = test_paragraph(&[text]);
        paragraph.set_writing_mode(WritingMode::VerticalRl);
        paragraph.children_mut()[0].font_size = 20.0;
        content.add_paragraph(paragraph);
        content
    }

    #[test]
    fn vertical_auto_height_reflows_when_only_height_changes() {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let mut content = vertical_content("あいうえおかきくけこ", GrowType::AutoHeight);
        let tall = content.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 240.0));
        let short = content.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 60.0));
        assert!(short.width > tall.width);
        assert_eq!(short.height, 60.0);
        let restored = content.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 240.0));
        assert_eq!(restored.width, tall.width);
    }

    #[test]
    fn vertical_height_modifier_requires_layout() {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let content = vertical_content("あいうえお", GrowType::AutoHeight);
        let shape = text_shape_with_cached_layout(content);
        assert!(modifier_changes_text_layout(
            &shape,
            &Matrix::scale((1.0, 0.5))
        ));
    }

    #[test]
    fn empty_auto_height_preserves_the_wrap_budget() {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let mut vertical = vertical_content("", GrowType::AutoHeight);
        let size = vertical.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 200.0));
        assert_eq!(size.height, 200.0);
        assert!(size.width >= 20.0);
        let mut horizontal = vertical_content("", GrowType::AutoHeight);
        horizontal.paragraphs_mut()[0].set_writing_mode(WritingMode::HorizontalTb);
        let size = horizontal.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 200.0));
        assert_eq!(size.width, 100.0);
    }

    #[test]
    fn empty_fixed_text_preserves_its_container() {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let mut content = vertical_content("", GrowType::Fixed);
        let size = content.update_layout(Rect::from_xywh(10.0, 20.0, 100.0, 200.0));
        assert_eq!((size.width, size.height), (100.0, 200.0));
    }

    #[test]
    fn a_warichu_span_shows_no_ruby_and_reserves_no_room_for_it() {
        let span = TextSpan {
            text: "割注".to_string(),
            ruby: "よみ".to_string(),
            warichu: true,
            annotation_clearance: AnnotationClearance::Auto,
            ..TextSpan::default()
        };
        assert!(!span.has_ruby());
        assert_eq!(span.annotation_room_em(), 0.0);
    }

    #[test]
    fn vertical_align_top_keeps_the_content_at_the_origin() {
        assert_eq!(vertical_align_offset(200.0, 60.0, VerticalAlign::Top), 0.0);
    }

    #[test]
    fn vertical_align_center_takes_half_the_slack() {
        assert_eq!(
            vertical_align_offset(200.0, 60.0, VerticalAlign::Center),
            70.0
        );
    }

    #[test]
    fn vertical_align_bottom_takes_all_the_slack() {
        assert_eq!(
            vertical_align_offset(200.0, 60.0, VerticalAlign::Bottom),
            140.0
        );
    }

    #[test]
    fn vertical_align_offset_is_negative_when_content_overflows() {
        assert_eq!(
            vertical_align_offset(60.0, 200.0, VerticalAlign::Center),
            -70.0
        );
        assert_eq!(
            vertical_align_offset(60.0, 200.0, VerticalAlign::Bottom),
            -140.0
        );
    }

    fn capitalize(text: &str) -> String {
        apply_text_transform_with_source_ranges(
            text,
            Browser::Chrome as u8,
            Some(TextTransform::Capitalize),
        )
        .text
    }

    fn filter_ignored_chars(text: &str, browser: u8) -> String {
        apply_text_transform_with_source_ranges(text, browser, None).text
    }

    #[test]
    fn capitalize_basic_words() {
        assert_eq!(capitalize("hello world"), "Hello World");
    }

    #[test]
    fn capitalize_preserves_leading_whitespace() {
        assert_eq!(capitalize(" hello"), " Hello");
    }

    #[test]
    fn capitalize_preserves_trailing_whitespace() {
        assert_eq!(capitalize("hello "), "Hello ");
    }

    #[test]
    fn capitalize_preserves_multiple_spaces() {
        assert_eq!(capitalize("hello  world"), "Hello  World");
    }

    #[test]
    fn capitalize_whitespace_only() {
        assert_eq!(capitalize(" "), " ");
        assert_eq!(capitalize("  "), "  ");
    }

    #[test]
    fn capitalize_empty_string() {
        assert_eq!(capitalize(""), "");
    }

    #[test]
    fn capitalize_single_char() {
        assert_eq!(capitalize("a"), "A");
    }

    #[test]
    fn capitalize_already_uppercase() {
        assert_eq!(capitalize("HELLO WORLD"), "HELLO WORLD");
    }

    #[test]
    fn capitalize_preserves_tabs_and_newlines() {
        assert_eq!(capitalize("hello\tworld"), "Hello\tWorld");
        assert_eq!(capitalize("hello\nworld"), "Hello\nWorld");
    }

    #[test]
    fn capitalize_after_punctuation() {
        assert_eq!(capitalize("(readonly)"), "(Readonly)");
        assert_eq!(capitalize("hello-world"), "Hello-World");
        assert_eq!(capitalize("one/two/three"), "One/Two/Three");
    }

    #[test]
    fn capitalize_after_digits() {
        assert_eq!(capitalize("item1name"), "Item1Name");
    }

    #[test]
    fn ignored_chars_preserves_spaces() {
        assert_eq!(filter_ignored_chars("hello world", 0), "hello world");
    }

    #[test]
    fn ignored_chars_preserves_line_breaks() {
        assert_eq!(filter_ignored_chars("hello\nworld", 0), "hello\nworld");
        assert_eq!(filter_ignored_chars("hello\rworld", 0), "hello\rworld");
    }

    #[test]
    fn ignored_chars_preserves_tabs() {
        assert_eq!(filter_ignored_chars("hello\tworld", 0), "hello\tworld");
        assert_eq!(
            filter_ignored_chars("hello\tworld", Browser::Firefox as u8),
            "hello\tworld"
        );
    }

    #[test]
    fn ignored_chars_replaces_control_chars_chrome() {
        // U+0001 (SOH) should become space in non-Firefox
        assert_eq!(filter_ignored_chars("a\x01b", Browser::Chrome as u8), "a b");
    }

    #[test]
    fn ignored_chars_removes_control_chars_firefox() {
        assert_eq!(filter_ignored_chars("a\x01b", Browser::Firefox as u8), "ab");
    }

    fn test_paragraph(texts: &[&str]) -> Paragraph {
        let spans = texts
            .iter()
            .map(|text| {
                TextSpan::new(
                    text.to_string(),
                    FontFamily::new(Uuid::nil(), 400, crate::shapes::FontStyle::Normal),
                    14.0,
                    1.2,
                    0.0,
                    None,
                    None,
                    TextDirection::LTR,
                    400,
                    Uuid::nil(),
                    vec![],
                )
            })
            .collect();

        Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            spans,
        )
    }

    #[test]
    fn char_offsets_match_utf16_offsets_for_bmp_text() {
        let para = test_paragraph(&["Añadir"]);
        for offset in 0..=6 {
            assert_eq!(para.char_offset_to_utf16(offset), offset);
            assert_eq!(
                HorizontalOffsets::new(&para).source_to_builder(offset),
                offset
            );
            assert_eq!(horizontal_builder_to_source(&para, offset), offset);
        }
    }

    #[test]
    fn char_offsets_account_for_astral_characters() {
        let para = test_paragraph(&["a", "😀b"]);

        assert_eq!(para.char_offset_to_utf16(0), 0);
        assert_eq!(para.char_offset_to_utf16(1), 1);
        assert_eq!(para.char_offset_to_utf16(2), 3);
        assert_eq!(para.char_offset_to_utf16(3), 4);

        assert_eq!(horizontal_builder_to_source(&para, 0), 0);
        assert_eq!(horizontal_builder_to_source(&para, 1), 1);
        assert_eq!(horizontal_builder_to_source(&para, 3), 2);
        assert_eq!(horizontal_builder_to_source(&para, 4), 3);
    }

    #[test]
    fn builder_offset_inside_a_surrogate_pair_rounds_to_a_char_boundary() {
        let para = test_paragraph(&["a😀b"]);
        assert_eq!(horizontal_builder_to_source(&para, 2), 2);
    }

    #[test]
    fn char_offsets_account_for_text_transforms() {
        // Skia lays out the transformed text, where "Straße" is "STRASSE".
        let mut para = test_paragraph(&["Straße"]);
        para.children_mut()[0].text_transform = Some(TextTransform::Uppercase);

        assert_eq!(para.char_offset_to_utf16(4), 4);
        assert_eq!(para.char_offset_to_utf16(6), 7);
        assert_eq!(para.char_offset_to_utf16(5), 6);
        assert_eq!(HorizontalOffsets::new(&para).source_to_builder(4), 4);
        assert_eq!(HorizontalOffsets::new(&para).source_to_builder(5), 6);
        assert_eq!(HorizontalOffsets::new(&para).source_to_builder(6), 7);
        assert_eq!(horizontal_builder_to_source(&para, 7), 6);
    }

    #[test]
    fn grapheme_offsets_cross_whole_emojis() {
        let para = test_paragraph(&["A👍🏽👨\u{200d}👩\u{200d}👧e\u{301}"]);
        let boundaries = [0, 1, 3, 8, 10];
        for pair in boundaries.windows(2) {
            assert_eq!(para.next_grapheme_offset(pair[0]), pair[1]);
            assert_eq!(para.prev_grapheme_offset(pair[1]), pair[0]);
        }
        assert_eq!(para.next_grapheme_offset(2), 3);
        assert_eq!(para.prev_grapheme_offset(2), 1);
        assert_eq!(para.next_grapheme_offset(10), 10);
        assert_eq!(para.prev_grapheme_offset(0), 0);
    }

    #[test]
    fn grapheme_offsets_cross_an_emoji_split_across_spans() {
        let para = test_paragraph(&["A👍", "🏽B"]);
        assert_eq!(para.next_grapheme_offset(1), 3);
        assert_eq!(para.prev_grapheme_offset(3), 1);
    }

    #[test]
    fn builder_range_covers_the_whole_glyph() {
        let para = test_paragraph(&["a😀b"]);
        let len_at = |offset| {
            HorizontalOffsets::new(&para).source_to_builder(offset + 1)
                - HorizontalOffsets::new(&para).source_to_builder(offset)
        };
        assert_eq!(len_at(0), 1);
        assert_eq!(len_at(1), 2);
        assert_eq!(len_at(2), 1);
    }

    fn sample_text_content() -> TextContent {
        let bounds = Rect::from_xywh(0.0, 0.0, 200.0, 100.0);
        let mut content = TextContent::new(bounds, GrowType::Fixed);
        content.add_paragraph(test_paragraph(&["hello"]));
        content
    }

    #[test]
    fn has_usable_paint_layout_false_when_paragraphs_empty() {
        let content = TextContent::new(Rect::from_xywh(0.0, 0.0, 100.0, 50.0), GrowType::Fixed);
        let shape = Shape::new(Uuid::nil());
        assert!(!content.has_usable_paint_layout(&shape));
    }

    #[test]
    fn has_usable_paint_layout_false_when_versions_mismatch() {
        let mut content = sample_text_content();
        content.layout.paragraphs = Rc::new(vec![vec![]]);
        content.layout_width = Some(200.0);
        content.layout_version = 1;
        content.content_version = 2;
        let mut shape = Shape::new(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 200.0, 100.0);
        assert!(!content.has_usable_paint_layout(&shape));
    }

    #[test]
    fn has_usable_paint_layout_true_when_cached_and_versions_match() {
        let mut content = sample_text_content();
        content.layout.paragraphs = Rc::new(vec![vec![]]);
        content.layout_width = Some(200.0);
        content.layout_version = 3;
        content.content_version = 3;
        let mut shape = Shape::new(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 200.0, 100.0);
        assert!(content.has_usable_paint_layout(&shape));
    }

    #[test]
    fn has_usable_paint_layout_false_when_selrect_width_changed() {
        let mut content = sample_text_content();
        content.layout.paragraphs = Rc::new(vec![vec![]]);
        content.layout_width = Some(200.0);
        content.layout_version = 3;
        content.content_version = 3;
        let mut shape = Shape::new(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 300.0, 100.0);
        assert!(!content.has_usable_paint_layout(&shape));
    }

    fn text_shape_with_cached_layout(content: TextContent) -> Shape {
        let mut shape = Shape::new(Uuid::nil());
        shape.set_shape_type(shapes::Type::Text(content));
        shape.set_selrect(0.0, 0.0, 200.0, 100.0);
        shape
    }

    #[test]
    fn has_usable_paint_layout_false_when_rotated_and_resized() {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let mut content = sample_text_content();
        content.layout.paragraphs = Rc::new(vec![vec![]]);
        content.layout_width = Some(200.0);
        content.layout_version = 3;
        content.content_version = 3;
        let base_shape = text_shape_with_cached_layout(content);
        let rotate = Matrix::rotate_deg(45.0);
        let resize = Matrix::scale((1.5, 1.0));
        let mut modifier = rotate;
        modifier.pre_concat(&resize);
        assert!(modifier_changes_text_layout(&base_shape, &modifier));
    }

    #[test]
    fn has_text_decorations_detects_underline() {
        let mut content = sample_text_content();
        content.paragraphs_mut()[0].children_mut()[0].text_decoration =
            Some(skia::textlayout::TextDecoration::UNDERLINE);
        assert!(content.has_text_decorations());
    }

    #[test]
    fn has_text_decorations_false_for_plain_text() {
        let content = sample_text_content();
        assert!(!content.has_text_decorations());
    }

    #[test]
    fn paint_content_for_selrect_borrows_when_bounds_match() {
        let content = sample_text_content();
        let selrect = Rect::from_xywh(10.0, 20.0, 200.0, 100.0);
        match content.paint_content_for_selrect(selrect) {
            Cow::Borrowed(_) => {}
            Cow::Owned(_) => panic!("expected borrowed content"),
        }
    }

    #[test]
    fn paint_content_for_selrect_rebounds_when_size_differs() {
        let content = sample_text_content();
        let selrect = Rect::from_xywh(0.0, 0.0, 300.0, 100.0);
        match content.paint_content_for_selrect(selrect) {
            Cow::Owned(rebound) => {
                assert_eq!(rebound.bounds().width(), 300.0);
                assert!(rebound.layout.needs_update());
            }
            Cow::Borrowed(_) => panic!("expected rebound content"),
        }
    }

    #[test]
    fn layout_paint_origin_set_when_layout_result_applied() {
        let mut content = sample_text_content();
        content.set_xywh(40.0, 60.0, 200.0, 100.0);
        let empty =
            TextContentLayoutResult(vec![], vec![], TextContentSize::new_with_size(200.0, 100.0));
        content.set_layout_from_result(empty, 200.0, 100.0);
        let selrect = Rect::from_xywh(40.0, 60.0, 200.0, 100.0);
        assert_eq!(
            content.cached_layout_paint_anchor(&selrect),
            Point::new(40.0, 60.0)
        );
        assert_eq!(
            content.cached_layout_paint_offset(&selrect),
            Point::new(0.0, 0.0)
        );
    }

    #[test]
    fn cached_layout_paint_offset_tracks_selrect_move() {
        let mut content = sample_text_content();
        content.layout_paint_origin = Some(Point::new(10.0, 20.0));
        // Simulate a move clone: bounds follow the new selrect, origin stays.
        content.set_xywh(110.0, 220.0, 200.0, 100.0);
        let selrect = Rect::from_xywh(110.0, 220.0, 200.0, 100.0);
        let offset = content.cached_layout_paint_offset(&selrect);
        assert_eq!(offset, Point::new(100.0, 200.0));
        assert_eq!(
            content.cached_layout_paint_anchor(&selrect),
            Point::new(10.0, 20.0)
        );
    }

    #[test]
    fn cached_layout_paint_offset_zero_without_origin() {
        let content = sample_text_content();
        let selrect = Rect::from_xywh(50.0, 75.0, 200.0, 100.0);
        assert_eq!(
            content.cached_layout_paint_offset(&selrect),
            Point::new(0.0, 0.0)
        );
        assert_eq!(
            content.cached_layout_paint_anchor(&selrect),
            Point::new(50.0, 75.0)
        );
    }

    #[test]
    fn layout_paint_origin_survives_bounds_transform_on_clone() {
        let mut content = sample_text_content();
        content.set_xywh(10.0, 20.0, 200.0, 100.0);
        content.layout_paint_origin = Some(Point::new(10.0, 20.0));
        content.layout.paragraphs = Rc::new(vec![vec![]]);
        content.layout_width = Some(200.0);
        content.layout_version = 1;
        content.content_version = 1;

        let mut moved = content.clone();
        let mut move_matrix = Matrix::new_identity();
        move_matrix.set_translate_x(50.0);
        move_matrix.set_translate_y(30.0);
        moved.transform(&move_matrix);

        assert_eq!(moved.bounds().x(), 60.0);
        assert_eq!(moved.bounds().y(), 50.0);
        assert!(Rc::ptr_eq(
            &content.layout.paragraphs,
            &moved.layout.paragraphs
        ));
        let selrect = Rect::from_xywh(60.0, 50.0, 200.0, 100.0);
        assert_eq!(
            moved.cached_layout_paint_anchor(&selrect),
            Point::new(10.0, 20.0)
        );
        assert_eq!(
            moved.cached_layout_paint_offset(&selrect),
            Point::new(50.0, 30.0)
        );
    }

    #[test]
    fn layout_clone_shares_skia_paragraphs() {
        let mut layout = TextContentLayout::new();
        layout.paragraphs = Rc::new(vec![vec![]]);
        let cloned = layout.clone();
        assert!(Rc::ptr_eq(&layout.paragraphs, &cloned.paragraphs));
        assert!(cloned.paragraph_builders.is_empty());
    }

    #[test]
    fn layout_clear_empties_paragraphs() {
        let mut layout = TextContentLayout::new();
        layout.paragraphs = Rc::new(vec![vec![]]);
        layout.clear();
        assert!(layout.needs_update());
    }

    #[test]
    fn transformed_text_maps_expanded_scalars_to_their_source_range() {
        let transformed = apply_text_transform_with_source_ranges(
            "AßB",
            Browser::Chrome as u8,
            Some(TextTransform::Uppercase),
        );

        assert_eq!(transformed.text, "ASSB");
        assert_eq!(transformed.source_utf16_range(0..1), 0..1);
        assert_eq!(transformed.source_utf16_range(1..2), 1..2);
        assert_eq!(transformed.source_utf16_range(2..3), 1..2);
        assert_eq!(transformed.source_utf16_range(1..3), 1..2);
        assert_eq!(transformed.source_utf16_range(3..4), 2..3);
    }
}
