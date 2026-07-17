use crate::render::text::calculate_decoration_metrics;
use crate::render::text::segment_advance_bounds_for_line;
use crate::render::text::span_overlaps_line;
use crate::{
    math::{Bounds, Matrix, Rect},
    render::{default_font, DEFAULT_EMOJI_FONT},
    utils::{get_fallback_fonts, Browser},
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

use std::cell::Cell;
use std::collections::HashSet;

use super::FontFamily;
use crate::math::Point;
use crate::shapes::{self, merge_fills, Shape, VerticalAlign};
use crate::utils::get_font_collection;
use crate::Uuid;

// TODO: maybe move this to the wasm module?
pub type ParagraphBuilderGroup = Vec<ParagraphBuilder>;

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

    pub fn new_without_affinity(paragraph: usize, offset: usize) -> Self {
        Self {
            position_with_affinity: PositionWithAffinity {
                position: offset as i32,
                affinity: Affinity::Downstream,
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

#[derive(Debug)]
pub struct TextContentLayout {
    pub paragraph_builders: Vec<ParagraphBuilderGroup>,
    pub paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
    cached_extrect: Cell<Option<CachedExtrect>>,
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
            paragraphs: vec![],
            cached_extrect: Cell::new(None),
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
            paragraphs: vec![],
            cached_extrect: Cell::new(None),
        }
    }

    pub fn set(
        &mut self,
        paragraph_builders: Vec<ParagraphBuilderGroup>,
        paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
    ) {
        self.paragraph_builders = paragraph_builders;
        self.paragraphs = paragraphs;
        self.cached_extrect.set(None);
    }

    pub fn needs_update(&self) -> bool {
        self.paragraph_builders.is_empty() || self.paragraphs.is_empty()
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

fn vertical_align_offset(container_h: f32, content_h: f32, valign: VerticalAlign) -> f32 {
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
    build_paragraphs_from_paragraph_builders_with_gutters(paragraph_builders, width, &[])
}

pub fn build_paragraphs_from_paragraph_builders_with_gutters(
    paragraph_builders: &mut [ParagraphBuilderGroup],
    width: f32,
    gutters: &[f32],
) -> Vec<Vec<skia::textlayout::Paragraph>> {
    let paragraphs = paragraph_builders
        .iter_mut()
        .enumerate()
        .map(|(idx, builders)| {
            let gutter = gutters.get(idx).copied().unwrap_or(0.0);
            let layout_width = (width - gutter).max(1.0);
            builders
                .iter_mut()
                .map(|builder| {
                    let mut paragraph = builder.build();
                    // For auto-width, always layout with infinite width first to get intrinsic width
                    paragraph.layout(layout_width);
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
    pub size: TextContentSize,
    pub layout: TextContentLayout,
    content_version: u64,
    layout_version: u64,
    layout_width: Option<f32>,
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
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
        }
    }

    pub fn new_bounds(&self, bounds: Rect) -> Self {
        let paragraphs = self.paragraphs.clone();
        let grow_type = self.grow_type;
        Self {
            paragraphs,
            bounds,
            grow_type,
            size: TextContentSize::new_with_size(bounds.width(), bounds.height()),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
        }
    }

    pub fn bounds(&self) -> Rect {
        self.bounds
    }

    pub fn set_xywh(&mut self, x: f32, y: f32, w: f32, h: f32) {
        self.bounds = Rect::from_xywh(x, y, w, h);
    }

    pub fn add_paragraph(&mut self, paragraph: Paragraph) {
        self.paragraphs.push(paragraph);
        self.content_version = self.content_version.wrapping_add(1);
    }

    pub fn paragraphs(&self) -> &[Paragraph] {
        &self.paragraphs
    }

    pub fn has_non_ascii(&self) -> bool {
        self.paragraphs
            .iter()
            .flat_map(|p| p.children())
            .any(|span| !span.text.is_ascii())
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

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
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

        for (para_idx, group) in paragraphs.iter().enumerate() {
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

                // `list-style-position: outside` renders list markers in the
                // left margin area (outside the paragraph's text box).
                // Extend the tight bounds to include that marker area.
                if let Some(content_para) = self.paragraphs().get(para_idx) {
                    if content_para.list_style.is_active()
                        && !content_para.list_style_position().is_inside()
                    {
                        // Extend on the left side only. Using `min_x` keeps
                        // us consistent with the line-metric based bounds
                        // computed above (which already include any internal
                        // alignment offsets).
                        min_x -= content_para.list_marker_column();
                    }
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
        if self.grow_type() == GrowType::AutoWidth {
            return self.content_rect(selrect, valign);
        }

        let tight = if !self.layout.paragraphs.is_empty() {
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

    pub fn content_rect(&self, selrect: &Rect, valign: VerticalAlign) -> Rect {
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

    pub fn get_caret_position_from_shape_coords(
        &self,
        point: &Point,
    ) -> Option<TextPositionWithAffinity> {
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
                let gutter = self
                    .paragraphs()
                    .get(paragraph_index)
                    .map(|p| p.list_gutter())
                    .unwrap_or(0.0);
                let para_pt = Point::new(point.x - gutter, point.y - start_y);
                let position_with_affinity =
                    layout_paragraph.get_glyph_position_at_coordinate((para_pt.x, para_pt.y));
                if let Some(paragraph) = self.paragraphs().get(paragraph_index) {
                    let skia_index = position_with_affinity.position.max(0) as usize;
                    let text_offset = paragraph.skia_index_to_text(skia_index);
                    let mut normalized_position = position_with_affinity;
                    normalized_position.position = text_offset as i32;

                    return Some(TextPositionWithAffinity::new(
                        normalized_position,
                        paragraph_index,
                        text_offset,
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
    ) -> Option<TextPositionWithAffinity> {
        let shape_rel_point = Shape::get_relative_point(point, view_matrix, shape_matrix)?;
        self.get_caret_position_from_shape_coords(&shape_rel_point)
    }

    /// Builds the ParagraphBuilders necessary to render
    /// this text.
    pub fn paragraph_builder_group_from_text(
        &self,
        use_shadow: Option<bool>,
    ) -> Vec<ParagraphBuilderGroup> {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_group = Vec::new();

        for paragraph in self.paragraphs() {
            let paragraph_style = paragraph.paragraph_to_style();
            let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
            paragraph.push_list_marker_placeholder(&mut builder);
            let mut has_text = false;
            for span in paragraph.children() {
                let remove_alpha = use_shadow.unwrap_or(false) && !span.is_transparent();
                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    remove_alpha,
                    paragraph.line_height(),
                );
                let text: String = span.apply_text_transform();
                if !text.is_empty() {
                    has_text = true;
                }
                builder.push_style(&text_style);
                builder.add_text(&text);
            }
            if !has_text {
                builder.add_text(" ");
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    /// Creates paragraph builders with always-opaque paint (BLACK @ alpha 255).
    /// Used as a clip mask for inner stroke rendering.
    pub fn paragraph_builder_group_opaque(&self) -> Vec<ParagraphBuilderGroup> {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_group = Vec::new();

        for paragraph in self.paragraphs() {
            let paragraph_style = paragraph.paragraph_to_style();
            let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
            paragraph.push_list_marker_placeholder(&mut builder);
            let mut has_text = false;
            for span in paragraph.children() {
                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    true, // always opaque
                    paragraph.line_height(),
                );
                let text: String = span.apply_text_transform();
                if !text.is_empty() {
                    has_text = true;
                }
                builder.push_style(&text_style);
                builder.add_text(&text);
            }
            if !has_text {
                builder.add_text(" ");
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    fn list_gutters(&self) -> Vec<f32> {
        self.paragraphs.iter().map(|p| p.list_gutter()).collect()
    }

    /// Performs an Auto Width text layout.
    fn text_layout_auto_width(&self) -> TextContentLayoutResult {
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let gutters = self.list_gutters();

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, f32::MAX);

        let paragraphs = build_paragraphs_from_paragraph_builders_with_gutters(
            &mut paragraph_builders,
            f32::MAX,
            &gutters,
        );

        let (width, height) =
            paragraphs
                .iter()
                .flatten()
                .fold((0.0, 0.0), |(auto_width, auto_height), paragraph| {
                    (
                        f32::max(paragraph.longest_line(), auto_width),
                        auto_height + paragraph.height(),
                    )
                });

        // Include the widest list gutter so auto-width still fits markers.
        let max_gutter = self
            .paragraphs()
            .iter()
            .map(|p| {
                if !p.list_style().is_active() {
                    return 0.0;
                }
                if p.list_style_position().is_inside() {
                    p.list_indent_offset()
                } else {
                    // `outside` renders the marker in the left margin area,
                    // which needs space when sizing auto-width shapes.
                    p.list_indent_offset() + p.list_marker_column()
                }
            })
            .fold(0.0_f32, f32::max);
        let width = width + max_gutter;

        let size = TextContentSize::new_with_normalized_line_height(
            width.ceil(),
            height.ceil(),
            width.ceil(),
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Private function that performs
    /// Performs an Auto Height text layout.
    fn text_layout_auto_height(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let gutters = self.list_gutters();

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders_with_gutters(
            &mut paragraph_builders,
            width,
            &gutters,
        );
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
        let gutters = self.list_gutters();

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders_with_gutters(
            &mut paragraph_builders,
            width,
            &gutters,
        );
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

    pub fn set_layout_from_result(
        &mut self,
        result: TextContentLayoutResult,
        default_width: f32,
        default_height: f32,
    ) {
        self.layout.set(result.0, result.1);
        self.size
            .copy_finite_size(result.2, default_width, default_height);
    }

    pub fn force_next_layout_update(&mut self) {
        self.layout_width = None;
        self.layout.cached_extrect.set(None);
    }

    pub fn update_layout(&mut self, selrect: Rect) -> TextContentSize {
        if !self.layout.needs_update()
            && self.layout_version == self.content_version
            && self
                .layout_width
                .is_some_and(|w| (w - selrect.width()).abs() < f32::EPSILON)
        {
            return self.size;
        }

        self.size.set_size(selrect.width(), selrect.height());

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

        if self.is_empty() {
            let (placeholder_width, placeholder_height) = self.placeholder_dimensions(selrect);
            self.size.width = placeholder_width;
            self.size.height = placeholder_height;
            self.size.max_width = placeholder_width;
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
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
            content_version: 0,
            layout_version: 0,
            layout_width: None,
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
#[derive(Debug, PartialEq, Clone, Copy, Default)]
#[repr(u8)]
pub enum ListStyle {
    #[default]
    None = 0,
    Bullet = 1,
    Numbered = 2,
}

impl ListStyle {
    pub fn is_active(self) -> bool {
        !matches!(self, Self::None)
    }
}

/// CSS `list-style-position` for list markers.
/// See https://www.w3schools.com/cssref/pr_list-style-position.php
#[derive(Debug, PartialEq, Clone, Copy, Default)]
#[repr(u8)]
pub enum ListStylePosition {
    Outside = 0,
    #[default]
    Inside = 1,
}

impl ListStylePosition {
    pub fn is_inside(self) -> bool {
        matches!(self, Self::Inside)
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Outside => "outside",
            Self::Inside => "inside",
        }
    }
}

impl From<u8> for ListStylePosition {
    fn from(value: u8) -> Self {
        match value {
            1 => Self::Inside,
            _ => Self::Outside,
        }
    }
}

/// Maximum nesting depth for list items (levels 0..=4).
pub const MAX_LIST_INDENT: u8 = 4;

/// Default horizontal inset per list indent level, in canvas units (pixels).
pub const LIST_INDENT_STEP: f32 = 24.0;

#[derive(Debug, PartialEq, Clone)]
pub struct Paragraph {
    text_align: TextAlign,
    text_direction: TextDirection,
    text_decoration: Option<TextDecoration>,
    text_transform: Option<TextTransform>,
    line_height: f32,
    letter_spacing: f32,
    list_style: ListStyle,
    list_indent: u8,
    list_style_position: ListStylePosition,
    children: Vec<TextSpan>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self {
            text_align: TextAlign::default(),
            text_direction: TextDirection::LTR,
            text_decoration: None,
            text_transform: None,
            line_height: 1.0,
            letter_spacing: 0.0,
            list_style: ListStyle::None,
            list_indent: 0,
            list_style_position: ListStylePosition::Inside,
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
        list_style: ListStyle,
        list_indent: u8,
        list_style_position: ListStylePosition,
        children: Vec<TextSpan>,
    ) -> Self {
        Self {
            text_align,
            text_direction,
            text_decoration,
            text_transform,
            line_height,
            letter_spacing,
            list_style,
            list_indent: list_indent.min(MAX_LIST_INDENT),
            list_style_position,
            children,
        }
    }

    pub fn children(&self) -> &[TextSpan] {
        &self.children
    }

    pub fn children_mut(&mut self) -> &mut Vec<TextSpan> {
        &mut self.children
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

    pub fn list_style(&self) -> ListStyle {
        self.list_style
    }

    pub fn list_indent(&self) -> u8 {
        self.list_indent
    }

    pub fn list_style_position(&self) -> ListStylePosition {
        self.list_style_position
    }

    pub fn set_list_style(&mut self, list_style: ListStyle) {
        self.list_style = list_style;
    }

    pub fn set_list_indent(&mut self, list_indent: u8) {
        self.list_indent = list_indent.min(MAX_LIST_INDENT);
    }

    #[allow(dead_code)]
    pub fn set_list_style_position(&mut self, list_style_position: ListStylePosition) {
        self.list_style_position = list_style_position;
    }

    /// Horizontal offset for nested list levels (does not affect marker-to-text gap).
    pub fn list_indent_offset(&self) -> f32 {
        if self.list_style.is_active() {
            LIST_INDENT_STEP * self.list_indent as f32
        } else {
            0.0
        }
    }

    /// Fixed width of the marker column (bullet/number to text gap).
    pub fn list_marker_column(&self) -> f32 {
        if self.list_style.is_active() {
            LIST_INDENT_STEP
        } else {
            0.0
        }
    }

    /// Left inset for layout/positioning.
    /// - outside: indent + marker column (marker lives outside the text box)
    /// - inside: indent only (marker uses first-line text indent)
    pub fn list_gutter(&self) -> f32 {
        if !self.list_style.is_active() {
            0.0
        } else if self.list_style_position.is_inside() {
            self.list_indent_offset()
        } else {
            // `outside`: the marker is rendered in the margin area to the left
            // of the text box, so it should not reduce the paragraph's text
            // layout width.
            self.list_indent_offset()
        }
    }

    /// Skia reserves one index for the list-marker placeholder on inside lists.
    pub fn list_skia_index_offset(&self) -> usize {
        if self.list_style.is_active() && self.list_style_position.is_inside() {
            1
        } else {
            0
        }
    }

    pub fn text_offset_to_skia(&self, text_offset: usize) -> usize {
        text_offset.saturating_add(self.list_skia_index_offset())
    }

    pub fn skia_index_to_text(&self, skia_index: usize) -> usize {
        skia_index.saturating_sub(self.list_skia_index_offset())
    }

    /// Paragraph-local X where typed text begins (after an inside-list marker).
    pub fn list_text_start_x(&self) -> f32 {
        if self.list_style.is_active() && self.list_style_position.is_inside() {
            self.list_marker_column()
        } else {
            0.0
        }
    }

    /// Caret position in paragraph-local coordinates.
    pub fn caret_rect_in_laid_out_paragraph(
        &self,
        laid_out_para: &skia::textlayout::Paragraph,
        char_pos: usize,
        para_char_count: usize,
    ) -> (f32, f32, f32, f32) {
        use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};

        if char_pos >= para_char_count && para_char_count > 0 {
            let skia_start = self.text_offset_to_skia(para_char_count.saturating_sub(1));
            let skia_end = self
                .text_offset_to_skia(para_char_count)
                .max(skia_start + 1);
            let rects = laid_out_para.get_rects_for_range(
                skia_start..skia_end,
                RectHeightStyle::Max,
                RectWidthStyle::Tight,
            );
            if let Some(r) = rects.first() {
                let rect = &r.rect;
                return (rect.right(), rect.top(), rect.width(), rect.height());
            }
            return (
                laid_out_para.longest_line(),
                0.0,
                1.0,
                laid_out_para.height(),
            );
        }

        let skia_start = self.text_offset_to_skia(char_pos);
        let skia_end = self
            .text_offset_to_skia(char_pos.saturating_add(1))
            .max(skia_start + 1);
        let rects = laid_out_para.get_rects_for_range(
            skia_start..skia_end,
            RectHeightStyle::Max,
            RectWidthStyle::Tight,
        );
        if let Some(r) = rects.first() {
            let rect = &r.rect;
            return (rect.left(), rect.top(), rect.width(), rect.height());
        }

        (self.list_text_start_x(), 0.0, 1.0, laid_out_para.height())
    }

    pub fn paragraph_to_style(&self) -> ParagraphStyle {
        let mut style = ParagraphStyle::default();

        style.set_height(self.line_height);
        style.set_text_align(self.text_align);
        style.set_text_direction(self.text_direction);
        style.set_replace_tab_characters(true);
        style.set_apply_rounding_hack(true);
        style.set_text_height_behavior(skia::textlayout::TextHeightBehavior::All);
        style
    }

    /// For `list-style-position: inside`, reserve space for the marker on the
    /// first line only (wrapped lines start under the marker, like CSS).
    pub fn push_list_marker_placeholder(&self, builder: &mut ParagraphBuilder) {
        if self.list_style.is_active() && self.list_style_position.is_inside() {
            let width = self.list_marker_column();
            let placeholder = PlaceholderStyle::new(
                width,
                1.0,
                PlaceholderAlignment::Baseline,
                TextBaseline::Alphabetic,
                0.0,
            );
            builder.add_placeholder(&placeholder);
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.letter_spacing *= value;
        self.children
            .iter_mut()
            .for_each(|l| l.scale_content(value));
    }
}

/// Capitalize the first letter of each word, preserving all original whitespace.
/// Matches CSS `text-transform: capitalize` behavior: a "word" starts after
/// any non-letter character (whitespace, punctuation, digits, symbols).
fn capitalize_words(text: &str) -> String {
    let mut result = String::with_capacity(text.len());
    let mut capitalize_next = true;
    for c in text.chars() {
        if c.is_alphabetic() {
            if capitalize_next {
                result.extend(c.to_uppercase());
            } else {
                result.push(c);
            }
            capitalize_next = false;
        } else {
            result.push(c);
            capitalize_next = true;
        }
    }
    result
}

/// Filter control characters below U+0020, preserving line breaks.
/// Browser-dependent: Firefox drops them, others replace with space.
fn process_ignored_chars(text: &str, browser: u8) -> String {
    text.chars()
        .filter_map(|c| {
            if c == '\n' || c == '\r' || c == '\u{2028}' || c == '\u{2029}' {
                return Some(c);
            }
            if c < '\u{0020}' {
                if browser == Browser::Firefox as u8 {
                    None
                } else {
                    Some(' ')
                }
            } else {
                Some(c)
            }
        })
        .collect()
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
    pub fills: Vec<shapes::Fill>,
}

impl TextSpan {
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
        }
    }

    pub fn set_text(&mut self, text: String) {
        self.text = text;
    }

    pub fn to_style(
        &self,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        let mut paint = paint::Paint::default();

        if remove_alpha {
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
        } else {
            paint = merge_fills(&self.fills, *content_bounds);
        }

        let max_line_height = f32::max(paragraph_line_height, self.line_height);
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

    pub fn apply_text_transform(&self) -> String {
        let browser = crate::with_state!(state, { state.current_browser });
        let text = process_ignored_chars(&self.text, browser);
        match self.text_transform {
            Some(TextTransform::Uppercase) => text.to_uppercase(),
            Some(TextTransform::Lowercase) => text.to_lowercase(),
            Some(TextTransform::Capitalize) => capitalize_words(&text),
            None => text,
        }
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
    pub x: f32,
    pub y: f32,
    pub decorations: Vec<TextDecorationSegment>,
}

#[derive(Debug)]
pub struct TextLayoutData {
    pub position_data: Vec<PositionData>,
    pub paragraphs: Vec<ParagraphLayout>,
}

fn direction_to_int(direction: TextDirection) -> u32 {
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

    // 1. Build + layout each paragraph once, recording heights as we go.
    let mut paragraph_heights: Vec<f32> = Vec::new();
    let mut paragraph_gutters: Vec<f32> = Vec::with_capacity(paragraph_builder_groups.len());
    let mut built_groups: Vec<Vec<skia::textlayout::Paragraph>> =
        Vec::with_capacity(paragraph_builder_groups.len());
    for (para_idx, paragraph_builder_group) in paragraph_builder_groups.iter_mut().enumerate() {
        let gutter = text_paragraphs
            .get(para_idx)
            .map(|p| p.list_gutter())
            .unwrap_or(0.0);
        paragraph_gutters.push(gutter);
        let layout_width = (text_width - gutter).max(1.0);

        let group_len = paragraph_builder_group.len();
        let mut paragraph_offset_y = previous_line_height;
        let mut group_paragraphs: Vec<skia::textlayout::Paragraph> = Vec::with_capacity(group_len);
        for (builder_index, paragraph_builder) in paragraph_builder_group.iter_mut().enumerate() {
            let mut skia_paragraph = paragraph_builder.build();
            skia_paragraph.layout(layout_width);
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
    let vertical_offset = match shape.vertical_align() {
        VerticalAlign::Center => (selrect_height - total_text_height) / 2.0,
        VerticalAlign::Bottom => selrect_height - total_text_height,
        _ => 0.0,
    };
    let mut paragraph_layouts: Vec<ParagraphLayout> = Vec::new();
    let mut y_accum = base_y + vertical_offset;
    let fallback_fonts = get_fallback_fonts();
    let content_bounds = text_content.bounds();
    for (para_idx, group_paragraphs) in built_groups.into_iter().enumerate() {
        let gutter = paragraph_gutters.get(para_idx).copied().unwrap_or(0.0);
        let para_x = x + gutter;
        let content_para = text_paragraphs.get(para_idx);
        // For each paragraph in the group (e.g., fill, stroke, etc.)
        for skia_paragraph in group_paragraphs.into_iter() {
            // Calculate text decorations for this paragraph
            let mut decorations = Vec::new();
            let line_metrics = skia_paragraph.get_line_metrics();
            if let Some(content_para) = content_para {
                let mut utf16_cur = 0usize;
                for span in content_para.children() {
                    let text = span.apply_text_transform();
                    let text_utf16_len = text.encode_utf16().count();
                    if text_utf16_len == 0 {
                        continue;
                    }

                    let skia_start = content_para.text_offset_to_skia(utf16_cur);
                    let skia_end = content_para
                        .text_offset_to_skia(utf16_cur + text_utf16_len)
                        .max(skia_start + 1);

                    if let Some(deco) = span.text_decoration {
                        let text_style = span.to_style(
                            &content_bounds,
                            fallback_fonts,
                            false,
                            content_para.line_height(),
                        );

                        let list_index_offset = content_para.list_skia_index_offset();
                        for line in &line_metrics {
                            if !span_overlaps_line(
                                skia_start,
                                skia_end,
                                line.start_index,
                                line.end_index,
                                list_index_offset,
                            ) {
                                continue;
                            }

                            let style_metrics: Vec<_> = line
                                .get_style_metrics(line.start_index..line.end_index)
                                .into_iter()
                                .collect();
                            let line_baseline = y_accum + line.baseline as f32;
                            let line_top = line_baseline - line.ascent as f32;
                            let line_bottom = line_baseline + line.descent as f32;
                            let (
                                max_underline_thickness,
                                underline_y,
                                max_strike_thickness,
                                strike_y,
                            ) = calculate_decoration_metrics(&style_metrics, line_baseline);

                            let Some((segment_width, actual_x_offset)) =
                                segment_advance_bounds_for_line(
                                    &skia_paragraph,
                                    skia_start,
                                    skia_end,
                                    line.left as f32,
                                    line_top - y_accum,
                                    line_bottom - y_accum,
                                )
                            else {
                                continue;
                            };

                            let text_left = para_x + line.left as f32 + actual_x_offset;
                            let text_width = segment_width;
                            use skia::textlayout::TextDecoration;
                            if deco == TextDecoration::UNDERLINE {
                                decorations.push(TextDecorationSegment {
                                    kind: TextDecoration::UNDERLINE,
                                    text_style: text_style.clone(),
                                    y: underline_y.unwrap_or(line_baseline),
                                    thickness: max_underline_thickness,
                                    left: text_left,
                                    width: text_width,
                                });
                            }
                            if deco == TextDecoration::LINE_THROUGH {
                                decorations.push(TextDecorationSegment {
                                    kind: TextDecoration::LINE_THROUGH,
                                    text_style: text_style.clone(),
                                    y: strike_y.unwrap_or(line_baseline),
                                    thickness: max_strike_thickness,
                                    left: text_left,
                                    width: text_width,
                                });
                            }
                        }
                    }

                    utf16_cur += text_utf16_len;
                }
            }
            paragraph_layouts.push(ParagraphLayout {
                paragraph: skia_paragraph,
                x: para_x,
                y: y_accum,
                decorations,
            });
        }
        y_accum += paragraph_heights[para_idx];
    }

    // Calculate position data from paragraph_layouts
    if !skip_position_data {
        for (paragraph_index, para_layout) in paragraph_layouts.iter().enumerate() {
            let current_y = para_layout.y;
            let text_paragraph = text_paragraphs.get(paragraph_index);
            if let Some(text_para) = text_paragraph {
                let mut span_ranges: Vec<(usize, usize, usize)> = vec![];
                let mut cur = 0;
                for (span_index, span) in text_para.children().iter().enumerate() {
                    let text: String = span.apply_text_transform();
                    let text_len = text.encode_utf16().count();
                    span_ranges.push((cur, cur + text_len, span_index));
                    cur += text_len;
                }
                for (start, end, span_index) in span_ranges {
                    let skia_start = text_para.text_offset_to_skia(start);
                    let skia_end = text_para.text_offset_to_skia(end).max(skia_start + 1);
                    let rects = para_layout.paragraph.get_rects_for_range(
                        skia_start..skia_end,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    );

                    for textbox in rects {
                        let direction = textbox.direct;
                        let mut rect = textbox.rect;
                        let cy = rect.top + rect.height() / 2.0;

                        // Get byte positions from Skia's transformed text layout
                        let skia_pos_start = para_layout
                            .paragraph
                            .get_glyph_position_at_coordinate((rect.left + 0.1, cy))
                            .position as usize;
                        let skia_pos_end = para_layout
                            .paragraph
                            .get_glyph_position_at_coordinate((rect.right - 0.1, cy))
                            .position as usize;
                        let start_pos = text_para.skia_index_to_text(skia_pos_start) - start;
                        let end_pos = text_para.skia_index_to_text(skia_pos_end) - start;

                        rect.offset((para_layout.x, current_y));
                        position_data.push(PositionData {
                            paragraph: paragraph_index as u32,
                            span: span_index as u32,
                            start_pos: start_pos as u32,
                            end_pos: end_pos as u32,
                            x: rect.x(),
                            y: rect.y(),
                            width: rect.width(),
                            height: rect.height(),
                            direction: direction_to_int(direction),
                        });
                    }
                }
            }
        }
    }

    TextLayoutData {
        position_data,
        paragraphs: paragraph_layouts,
    }
}

pub fn calculate_position_data(
    shape: &Shape,
    text_content: &TextContent,
    skip_position_data: bool,
) -> Vec<PositionData> {
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

    #[test]
    fn capitalize_basic_words() {
        assert_eq!(capitalize_words("hello world"), "Hello World");
    }

    #[test]
    fn capitalize_preserves_leading_whitespace() {
        assert_eq!(capitalize_words(" hello"), " Hello");
    }

    #[test]
    fn capitalize_preserves_trailing_whitespace() {
        assert_eq!(capitalize_words("hello "), "Hello ");
    }

    #[test]
    fn capitalize_preserves_multiple_spaces() {
        assert_eq!(capitalize_words("hello  world"), "Hello  World");
    }

    #[test]
    fn capitalize_whitespace_only() {
        assert_eq!(capitalize_words(" "), " ");
        assert_eq!(capitalize_words("  "), "  ");
    }

    #[test]
    fn capitalize_empty_string() {
        assert_eq!(capitalize_words(""), "");
    }

    #[test]
    fn capitalize_single_char() {
        assert_eq!(capitalize_words("a"), "A");
    }

    #[test]
    fn capitalize_already_uppercase() {
        assert_eq!(capitalize_words("HELLO WORLD"), "HELLO WORLD");
    }

    #[test]
    fn capitalize_preserves_tabs_and_newlines() {
        assert_eq!(capitalize_words("hello\tworld"), "Hello\tWorld");
        assert_eq!(capitalize_words("hello\nworld"), "Hello\nWorld");
    }

    #[test]
    fn capitalize_after_punctuation() {
        assert_eq!(capitalize_words("(readonly)"), "(Readonly)");
        assert_eq!(capitalize_words("hello-world"), "Hello-World");
        assert_eq!(capitalize_words("one/two/three"), "One/Two/Three");
    }

    #[test]
    fn capitalize_after_digits() {
        assert_eq!(capitalize_words("item1name"), "Item1Name");
    }

    #[test]
    fn process_ignored_chars_preserves_spaces() {
        assert_eq!(process_ignored_chars("hello world", 0), "hello world");
    }

    #[test]
    fn process_ignored_chars_preserves_line_breaks() {
        assert_eq!(process_ignored_chars("hello\nworld", 0), "hello\nworld");
        assert_eq!(process_ignored_chars("hello\rworld", 0), "hello\rworld");
    }

    #[test]
    fn process_ignored_chars_replaces_control_chars_chrome() {
        // U+0001 (SOH) should become space in non-Firefox
        assert_eq!(
            process_ignored_chars("a\x01b", Browser::Chrome as u8),
            "a b"
        );
    }

    #[test]
    fn process_ignored_chars_removes_control_chars_firefox() {
        assert_eq!(
            process_ignored_chars("a\x01b", Browser::Firefox as u8),
            "ab"
        );
    }
}
