use crate::render::text::calculate_decoration_metrics;
use crate::{
    math::{Bounds, Matrix, Rect},
    render::{default_font, DEFAULT_EMOJI_FONT},
    utils::Browser,
};

use core::f32;
use macros::ToJs;
use skia_safe::textlayout::{
    PlaceholderAlignment, PlaceholderStyle, RectHeightStyle, RectWidthStyle, TextBaseline,
};
use skia_safe::{
    self as skia,
    paint::{self, Paint},
    textlayout::Affinity,
    textlayout::ParagraphBuilder,
    textlayout::ParagraphStyle,
    textlayout::PositionWithAffinity,
    Contains,
};

// CHANGEME: move all the custom japanese layout code to its own module

use std::cell::Cell;
use std::collections::HashSet;

use super::FontFamily;
use crate::math::Point;
use crate::shapes::{self, kinsoku, merge_fills, Shape, VerticalAlign};
use crate::utils::{get_fallback_fonts, get_font_collection};
use crate::Uuid;

// TODO: maybe move this to the wasm module?
pub type ParagraphBuilderGroup = Vec<ParagraphBuilder>;

pub const WARICHU_FONT_SCALE: f32 = 0.5;
pub const EMPHASIS_FONT_SCALE: f32 = 0.5;
const HORIZONTAL_WARICHU_BUILDER_LEN: usize = 3;
const HORIZONTAL_WARICHU_STYLE_ANCHOR: char = '\u{00A0}';
const HORIZONTAL_WARICHU_BREAK_ANCHOR: char = '\u{200B}';

/// Add a span to a horizontal paragraph builder. Warichu is represented by a
/// single inline placeholder so the two annotation lines wrap as one unit.
/// The actual glyphs are painted after SkParagraph has positioned the box.
pub(crate) fn add_horizontal_span(
    builder: &mut ParagraphBuilder,
    span: &TextSpan,
    builder_text: &str,
    text_style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) {
    if span.warichu && span.text.chars().count() >= 2 {
        let text = span.apply_text_transform();
        let split = super::text_vertical::warichu_split_chars(&text);
        let split_byte = text
            .char_indices()
            .nth(split)
            .map(|(index, _)| index)
            .unwrap_or(text.len());
        let (first, second) = text.split_at(split_byte);
        let mut mini_style = text_style.clone();
        mini_style.set_font_size(span.font_size * WARICHU_FONT_SCALE);
        mini_style.set_height(1.0);
        mini_style.set_height_override(true);
        mini_style.set_letter_spacing(span.letter_spacing * WARICHU_FONT_SCALE);
        let measure = |line: &str| {
            let mut mini = ParagraphBuilder::new(&ParagraphStyle::default(), fonts);
            mini.push_style(&mini_style);
            mini.add_text(line);
            let mut paragraph = mini.build();
            paragraph.layout(f32::MAX);
            paragraph.longest_line()
        };
        let width = measure(first).max(measure(second)).max(0.01);
        let height = span.font_size.max(0.01);
        builder.add_placeholder(&PlaceholderStyle::new(
            width,
            height,
            PlaceholderAlignment::Middle,
            TextBaseline::Alphabetic,
            height,
        ));
        // SkParagraph does not expose a placeholder's TextStyle through line
        // metrics. A near-zero, inkless NBSP preserves the exact
        // fill/stroke/shadow style for the custom paint pass; the following
        // zero-width space restores a legal wrapping boundary after the
        // atomic placeholder.
        let mut anchor_style = text_style.clone();
        anchor_style.set_font_size(0.01);
        anchor_style.set_height(0.01);
        anchor_style.set_height_override(true);
        anchor_style.set_letter_spacing(0.0);
        builder.push_style(&anchor_style);
        builder.add_text(HORIZONTAL_WARICHU_STYLE_ANCHOR.to_string());
        builder.add_text(HORIZONTAL_WARICHU_BREAK_ANCHOR.to_string());
    } else {
        builder.add_text(builder_text);
    }
}

#[derive(Debug, Clone)]
pub(crate) struct HorizontalSpanRange {
    pub span: usize,
    pub builder_start: usize,
    pub builder_end: usize,
    pub shifted_start: usize,
    pub source_start: usize,
    pub source_end: usize,
    pub warichu: bool,
    pub style_anchor_start: usize,
}

/// Ranges shared by layout, position-data and editor mapping. `shifted_*`
/// addresses the normal kinsoku-adjusted paragraph text, while `builder_*`
/// addresses the paragraph where a whole warichu span occupies one U+FFFC.
pub(crate) fn horizontal_span_ranges(paragraph: &Paragraph) -> Vec<HorizontalSpanRange> {
    let (span_texts, offset_map) = paragraph.layout_span_texts();
    let mut builder_cursor = 0usize;
    let mut builder_byte_cursor = 0usize;
    let mut shifted_cursor = 0usize;
    paragraph
        .children()
        .iter()
        .zip(span_texts)
        .enumerate()
        .map(|(span_index, (span, text))| {
            let shifted_start = shifted_cursor;
            shifted_cursor += text.encode_utf16().count();
            let shifted_end = shifted_cursor;
            let source_start = offset_map.to_original(shifted_start);
            let source_end = offset_map.to_original(shifted_end);
            let warichu = span.warichu && span.text.chars().count() >= 2;
            let builder_start = builder_cursor;
            let style_anchor_start = if warichu {
                builder_byte_cursor + '\u{FFFC}'.len_utf8()
            } else {
                builder_byte_cursor
            };
            builder_cursor += if warichu {
                HORIZONTAL_WARICHU_BUILDER_LEN
            } else {
                shifted_end - shifted_start
            };
            builder_byte_cursor += if warichu {
                '\u{FFFC}'.len_utf8()
                    + HORIZONTAL_WARICHU_STYLE_ANCHOR.len_utf8()
                    + HORIZONTAL_WARICHU_BREAK_ANCHOR.len_utf8()
            } else {
                text.len()
            };
            HorizontalSpanRange {
                span: span_index,
                builder_start,
                builder_end: builder_cursor,
                shifted_start,
                source_start,
                source_end,
                warichu,
                style_anchor_start,
            }
        })
        .collect()
}

fn source_char_boundaries(paragraph: &Paragraph) -> Vec<usize> {
    let mut boundaries = vec![0usize];
    for span in paragraph.children() {
        for character in span.text.chars() {
            boundaries.push(boundaries.last().copied().unwrap_or(0) + character.len_utf16());
        }
    }
    boundaries
}

pub(crate) fn horizontal_source_to_builder(
    paragraph: &Paragraph,
    source_char_offset: usize,
) -> usize {
    let boundaries = source_char_boundaries(paragraph);
    let source_utf16 = boundaries
        .get(source_char_offset)
        .copied()
        .unwrap_or_else(|| boundaries.last().copied().unwrap_or(0));
    let (_, offset_map) = paragraph.layout_span_texts();
    let ranges = horizontal_span_ranges(paragraph);
    let Some(range) = ranges
        .iter()
        .find(|range| source_utf16 >= range.source_start && source_utf16 <= range.source_end)
    else {
        return ranges.last().map(|range| range.builder_end).unwrap_or(0);
    };
    if range.warichu {
        return if source_utf16 >= range.source_end {
            range.builder_end
        } else {
            range.builder_start
        };
    }
    let shifted = offset_map.to_shifted(source_utf16);
    range.builder_start + shifted.saturating_sub(range.shifted_start)
}

pub(crate) fn horizontal_builder_to_source(paragraph: &Paragraph, builder_offset: usize) -> usize {
    let (_, offset_map) = paragraph.layout_span_texts();
    let ranges = horizontal_span_ranges(paragraph);
    let source_utf16 = ranges
        .iter()
        .find(|range| builder_offset >= range.builder_start && builder_offset <= range.builder_end)
        .map(|range| {
            if range.warichu {
                if builder_offset > range.builder_start {
                    range.source_end
                } else {
                    range.source_start
                }
            } else {
                let within = builder_offset
                    .saturating_sub(range.builder_start)
                    .min(range.builder_end - range.builder_start);
                offset_map.to_original(range.shifted_start + within)
            }
        })
        .unwrap_or_else(|| ranges.last().map(|range| range.source_end).unwrap_or(0));
    let boundaries = source_char_boundaries(paragraph);
    boundaries
        .partition_point(|boundary| *boundary < source_utf16)
        .min(boundaries.len().saturating_sub(1))
}

fn horizontal_warichu_boxes<'a>(
    paragraph: &'a Paragraph,
    laid_out: &skia::textlayout::Paragraph,
) -> Vec<(&'a TextSpan, usize, usize, skia::Rect)> {
    let mut source_start = 0usize;
    let mut placeholders = laid_out.get_rects_for_placeholders().into_iter();
    let mut boxes = Vec::new();
    for span in paragraph.children() {
        let length = span.text.chars().count();
        if span.warichu && length >= 2 {
            if let Some(textbox) = placeholders.next() {
                boxes.push((span, source_start, source_start + length, textbox.rect));
            }
        }
        source_start += length;
    }
    boxes
}

pub(crate) fn horizontal_warichu_hit_test(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    point: Point,
) -> Option<usize> {
    for (span, source_start, _, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        if !rect.contains(&point) {
            continue;
        }
        let split = super::text_vertical::warichu_split_chars(&span.apply_text_transform());
        let total = span.text.chars().count();
        let second = point.y >= rect.top() + rect.height() / 2.0;
        let (line_start, line_len) = if second {
            (split, total - split)
        } else {
            (0, split)
        };
        let fraction = ((point.x - rect.left()) / rect.width().max(0.01)).clamp(0.0, 1.0);
        let within = (fraction * line_len as f32).round() as usize;
        return Some(source_start + line_start + within.min(line_len));
    }
    None
}

pub(crate) fn horizontal_warichu_caret_rect(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    source_offset: usize,
) -> Option<skia::Rect> {
    for (span, source_start, source_end, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        if source_offset < source_start || source_offset > source_end {
            continue;
        }
        let split = super::text_vertical::warichu_split_chars(&span.apply_text_transform());
        let local = source_offset - source_start;
        let total = source_end - source_start;
        let (line_start, line_len, top) = if local >= split {
            (split, total - split, rect.top() + rect.height() / 2.0)
        } else {
            (0, split, rect.top())
        };
        let within = local.saturating_sub(line_start).min(line_len);
        let char_width = rect.width() / line_len.max(1) as f32;
        return Some(skia::Rect::from_xywh(
            rect.left() + within as f32 * char_width,
            top,
            char_width,
            rect.height() / 2.0,
        ));
    }
    None
}

pub(crate) fn horizontal_warichu_range_rects(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    source_start: usize,
    source_end: usize,
) -> Vec<skia::Rect> {
    let mut rects = Vec::new();
    for (span, span_start, span_end, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        let selected_start = source_start.max(span_start);
        let selected_end = source_end.min(span_end);
        if selected_start >= selected_end {
            continue;
        }
        let split = super::text_vertical::warichu_split_chars(&span.apply_text_transform());
        for (line_start, line_end, top) in [
            (span_start, span_start + split, rect.top()),
            (
                span_start + split,
                span_end,
                rect.top() + rect.height() / 2.0,
            ),
        ] {
            let start = selected_start.max(line_start);
            let end = selected_end.min(line_end);
            if start >= end {
                continue;
            }
            let line_len = line_end - line_start;
            let char_width = rect.width() / line_len.max(1) as f32;
            rects.push(skia::Rect::from_xywh(
                rect.left() + (start - line_start) as f32 * char_width,
                top,
                (end - start) as f32 * char_width,
                rect.height() / 2.0,
            ));
        }
    }
    rects
}

pub(crate) fn horizontal_normal_selection_ranges(
    paragraph: &Paragraph,
    source_start: usize,
    source_end: usize,
) -> Vec<std::ops::Range<usize>> {
    let mut span_start = 0usize;
    paragraph
        .children()
        .iter()
        .filter_map(|span| {
            let span_end = span_start + span.text.chars().count();
            let selected_start = source_start.max(span_start);
            let selected_end = source_end.min(span_end);
            let warichu = span.warichu && span.text.chars().count() >= 2;
            span_start = span_end;
            if warichu || selected_start >= selected_end {
                return None;
            }
            Some(
                horizontal_source_to_builder(paragraph, selected_start)
                    ..horizontal_source_to_builder(paragraph, selected_end),
            )
        })
        .collect()
}

fn warichu_text_lines(text: &str) -> (&str, &str) {
    let split = super::text_vertical::warichu_split_chars(text);
    let split_byte = text
        .char_indices()
        .nth(split)
        .map(|(index, _)| index)
        .unwrap_or(text.len());
    text.split_at(split_byte)
}

fn warichu_mini_paragraph(
    text: &str,
    style: &skia::textlayout::TextStyle,
    width: f32,
) -> skia::textlayout::Paragraph {
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), get_font_collection());
    builder.push_style(style);
    builder.add_text(text);
    let mut paragraph = builder.build();
    paragraph.layout(width.max(0.01));
    paragraph
}

/// Paint the two horizontal warichu sub-lines into SkParagraph's inline
/// placeholder boxes. Reading order is top line then bottom line.
pub(crate) fn paint_horizontal_warichu(
    canvas: &skia::Canvas,
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    x: f32,
    y: f32,
) {
    let ranges = horizontal_span_ranges(paragraph);
    let placeholders = laid_out.get_rects_for_placeholders();
    let warichu_ranges: Vec<_> = ranges.iter().filter(|range| range.warichu).collect();
    if placeholders.len() != warichu_ranges.len() {
        return;
    }

    for (range, textbox) in warichu_ranges.into_iter().zip(placeholders) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        // Indexed style metrics expose builder UTF-8 byte positions even
        // though glyph/line ranges use UTF-16 offsets.
        let style_anchor = range.style_anchor_start
            ..range.style_anchor_start + HORIZONTAL_WARICHU_STYLE_ANCHOR.len_utf8();
        let style = laid_out.get_line_metrics().iter().find_map(|line| {
            line.get_style_metrics(style_anchor.clone())
                .into_iter()
                .next()
                .map(|(_, metric)| metric.text_style.clone())
        });
        let Some(mut style) = style else {
            continue;
        };
        style.set_font_size(span.font_size * WARICHU_FONT_SCALE);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(span.letter_spacing * WARICHU_FONT_SCALE);
        let transformed = span.apply_text_transform();
        let (first, second) = warichu_text_lines(&transformed);
        let rect = textbox.rect;
        let first_para = warichu_mini_paragraph(first, &style, rect.width());
        let second_para = warichu_mini_paragraph(second, &style, rect.width());
        let half_height = rect.height() / 2.0;
        first_para.paint(canvas, (x + rect.left(), y + rect.top()));
        second_para.paint(canvas, (x + rect.left(), y + rect.top() + half_height));
    }
}

pub(crate) fn emphasis_char_allowed(character: char) -> bool {
    !character.is_whitespace()
        && !crate::shapes::japanese::classify(character).is_emphasis_prohibited()
}

#[derive(Debug, Clone, Copy)]
struct HorizontalEmphasisPlacement {
    span: usize,
    mark: char,
    rect: skia::Rect,
}

/// Locate one horizontal emphasis mark above each eligible source character.
/// SkParagraph owns wrapping and bidi placement; querying each transformed
/// character range keeps the marks attached to the actual laid-out glyphs.
fn horizontal_emphasis_placements(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
) -> Vec<HorizontalEmphasisPlacement> {
    let (_, offset_map) = paragraph.layout_span_texts();
    let ranges = horizontal_span_ranges(paragraph);
    let mut placements = Vec::new();

    for range in ranges.iter().filter(|range| !range.warichu) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        let Some(mark) = span.text_emphasis.mark_char() else {
            continue;
        };
        let transformed = span.apply_text_transform();
        let mut local_utf16 = 0usize;
        for character in transformed.chars() {
            let next_utf16 = local_utf16 + character.len_utf16();
            if emphasis_char_allowed(character) {
                let shifted_start = offset_map.to_shifted(range.source_start + local_utf16);
                let shifted_end = offset_map.to_shifted(range.source_start + next_utf16);
                let builder_start =
                    range.builder_start + shifted_start.saturating_sub(range.shifted_start);
                let builder_end =
                    range.builder_start + shifted_end.saturating_sub(range.shifted_start);
                let scalar_rect = laid_out
                    .get_rects_for_range(
                        builder_start..builder_end,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    )
                    .into_iter()
                    .map(|textbox| textbox.rect)
                    .reduce(|mut rect, next| {
                        rect.join(next);
                        rect
                    });
                if let Some(rect) = scalar_rect {
                    placements.push(HorizontalEmphasisPlacement {
                        span: range.span,
                        mark,
                        rect,
                    });
                }
            }
            local_utf16 = next_utf16;
        }
    }
    placements
}

fn horizontal_span_style(
    laid_out: &skia::textlayout::Paragraph,
    range: &HorizontalSpanRange,
) -> Option<skia::textlayout::TextStyle> {
    // Indexed style metrics use builder UTF-8 byte positions (the same
    // convention used by the warichu style anchor above).
    let anchor = range.style_anchor_start..range.style_anchor_start + 1;
    laid_out.get_line_metrics().iter().find_map(|line| {
        line.get_style_metrics(anchor.clone())
            .into_iter()
            .next()
            .map(|(_, metric)| metric.text_style.clone())
    })
}

/// Paint horizontal emphasis marks (圏点 / bouten) above their base glyphs.
/// The base paragraph retains its normal metrics; interlinear collision and
/// automatic line-gap expansion remain a separate layout policy.
pub(crate) fn paint_horizontal_emphasis(
    canvas: &skia::Canvas,
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    x: f32,
    y: f32,
) {
    let ranges = horizontal_span_ranges(paragraph);
    let placements = horizontal_emphasis_placements(paragraph, laid_out);
    for range in ranges.iter().filter(|range| !range.warichu) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        let Some(mark) = span.text_emphasis.mark_char() else {
            continue;
        };
        let Some(mut style) = horizontal_span_style(laid_out, range) else {
            continue;
        };
        style.set_font_size(span.font_size * EMPHASIS_FONT_SCALE);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(0.0);
        let mark_paragraph = warichu_mini_paragraph(&mark.to_string(), &style, f32::MAX);
        let mark_width = mark_paragraph.longest_line();
        let mark_height = mark_paragraph.height();
        for placement in placements
            .iter()
            .filter(|placement| placement.span == range.span && placement.mark == mark)
        {
            let mark_x = x + placement.rect.center_x() - mark_width / 2.0;
            let ruby_offset = if span.annotation_clearance.is_auto()
                && !span.ruby.trim().is_empty()
                && span.ruby_side == RubySide::Over
            {
                span.font_size * span.ruby_size.scale()
            } else {
                0.0
            };
            let mark_y = y + placement.rect.top() - mark_height - ruby_offset;
            mark_paragraph.paint(canvas, (mark_x, mark_y));
        }
    }
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

    /// Writing mode is a whole-shape property: the first paragraph
    /// decides the flow for all of them.
    pub fn is_vertical(&self) -> bool {
        self.paragraphs
            .first()
            .is_some_and(|p| p.writing_mode() == WritingMode::VerticalRl)
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

        for group in paragraphs {
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
        // Vertical writing bounds come from the vertical pass through
        // content_rect; the skparagraph line metrics below describe the
        // unused horizontal layout.
        if self.grow_type() == GrowType::AutoWidth || self.is_vertical() {
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
        // Vertical content anchors to the shape's right edge and always
        // aligns to the top (vertical-align along columns is deferred).
        if self.is_vertical() {
            let (width, height) = if self.grow_type() == GrowType::AutoWidth {
                (self.size.width, self.size.height)
            } else {
                (selrect.width(), selrect.height())
            };
            return Rect::from_xywh(selrect.right() - width, selrect.y(), width, height);
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

    pub fn get_caret_position_from_shape_coords(
        &self,
        point: &Point,
        vertical_align: VerticalAlign,
    ) -> Option<TextPositionWithAffinity> {
        // Vertical writing: resolve through the vertical pass. The point
        // arrives selrect-local; the content block is right-anchored.
        if self.is_vertical() {
            let bounds = self.bounds();
            let max_height = super::text_vertical::wrap_height(self, bounds.height());
            let layout = super::text_vertical::layout_from_content(self, max_height);
            let cx = point.x
                - super::text_vertical::block_axis_offset(
                    bounds.width(),
                    layout.width,
                    vertical_align,
                );
            let (paragraph, offset) = super::text_vertical::caret_from_point(&layout, cx, point.y)?;
            return Some(TextPositionWithAffinity::new_without_affinity(
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
                        return Some(TextPositionWithAffinity::new_without_affinity(
                            paragraph_index,
                            original_position,
                        ));
                    }
                }
                let mut position_with_affinity =
                    layout_paragraph.get_glyph_position_at_coordinate((para_pt.x, para_pt.y));
                if let Some(paragraph) = self.paragraphs().get(paragraph_index) {
                    // The laid-out paragraph reports offsets in the
                    // builder-text (kinsoku-shifted) space; translate
                    // back to original text offsets.
                    let original_position = horizontal_builder_to_source(
                        paragraph,
                        position_with_affinity.position as usize,
                    );
                    position_with_affinity.position = original_position as i32;

                    // Computed position keeps the current position in terms
                    // of number of characters of text. This is used to know
                    // in which span we are.
                    let mut computed_position: usize = 0;

                    // If paragraph has no spans, default to span 0, offset 0
                    if !paragraph.children().is_empty() {
                        for span in paragraph.children() {
                            let length = span.text.chars().count();
                            let start_position = computed_position;
                            let end_position = computed_position + length;
                            let current_position = original_position;

                            // Handle empty spans: if the span is empty and current position
                            // matches the start, this is the right span
                            if length == 0 && current_position == start_position {
                                break;
                            }

                            if start_position <= current_position
                                && end_position >= current_position
                            {
                                break;
                            }
                            computed_position += length;
                        }
                    }

                    return Some(TextPositionWithAffinity::new(
                        position_with_affinity,
                        paragraph_index,
                        original_position,
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
        vertical_align: VerticalAlign,
    ) -> Option<TextPositionWithAffinity> {
        let shape_rel_point = Shape::get_relative_point(point, view_matrix, shape_matrix)?;
        self.get_caret_position_from_shape_coords(&shape_rel_point, vertical_align)
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
            let mut has_text = false;
            let (span_texts, _) = paragraph.layout_span_texts();
            for (span, text) in paragraph.children().iter().zip(span_texts.iter()) {
                let remove_alpha = use_shadow.unwrap_or(false) && !span.is_transparent();
                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    remove_alpha,
                    paragraph.line_height(),
                );
                if !text.is_empty() {
                    has_text = true;
                }
                builder.push_style(&text_style);
                add_horizontal_span(&mut builder, span, text, &text_style, fonts);
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
            let mut has_text = false;
            let (span_texts, _) = paragraph.layout_span_texts();
            for (span, text) in paragraph.children().iter().zip(span_texts.iter()) {
                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    true, // always opaque
                    paragraph.line_height(),
                );
                if !text.is_empty() {
                    has_text = true;
                }
                builder.push_style(&text_style);
                add_horizontal_span(&mut builder, span, text, &text_style, fonts);
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
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, f32::MAX);

        let paragraphs =
            build_paragraphs_from_paragraph_builders(&mut paragraph_builders, f32::MAX);

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

        // Vertical writing sizes come from the vertical pass. Auto-width
        // fits both axes without wrapping. Auto-height keeps the shape height
        // as its wrap budget and grows width as columns advance right-to-left.
        // Fixed keeps both shape dimensions.
        if self.is_vertical() {
            match self.grow_type() {
                GrowType::AutoWidth => {
                    let max_height = super::text_vertical::wrap_height(self, selrect.height());
                    let (width, height) = super::text_vertical::measure_content(self, max_height);
                    self.size.width = width.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.height = height.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.max_width = self.size.width;
                }
                GrowType::AutoHeight => {
                    let max_height = super::text_vertical::wrap_height(self, selrect.height());
                    let (width, _) = super::text_vertical::measure_content(self, max_height);
                    self.size.width = width.ceil().max(DEFAULT_TEXT_CONTENT_SIZE);
                    self.size.height = selrect.height();
                    self.size.max_width = self.size.width;
                }
                GrowType::Fixed => {
                    self.size.set_size(selrect.width(), selrect.height());
                }
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

        // Vertical writing: hit-test against the laid-out cells directly
        // (absolute coordinates, right-anchored to the selrect).
        if self.is_vertical() {
            let max_height = super::text_vertical::wrap_height(self, shape.selrect.height());
            let layout = super::text_vertical::layout_from_content(self, max_height);
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

/// Block flow direction of a paragraph. Horizontal is the skparagraph
/// path; vertical-rl lays out columns top->bottom advancing right->left
/// through the custom vertical pass.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum WritingMode {
    #[default]
    HorizontalTb,
    VerticalRl,
}

/// Glyph orientation inside vertical flow: `Mixed` rotates non-CJK runs
/// sideways, `Upright` keeps every character upright. Ignored in
/// horizontal writing.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextOrientation {
    #[default]
    Mixed,
    Upright,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextCombineUpright {
    #[default]
    None,
    All,
    /// Combine runs of 2-4 consecutive ASCII or full-width digits into one upright
    /// composite; other characters keep the normal vertical layout.
    Digits,
    /// Like `Digits` but only runs of exactly 2 digits combine
    /// (CSS `text-combine-upright: digits 2`).
    Digits2,
    /// Like `Digits` but runs of 2-3 digits combine.
    Digits3,
}

impl TextCombineUpright {
    /// Longest digit run that combines, when digits mode is active.
    pub fn digits_max(self) -> Option<usize> {
        match self {
            TextCombineUpright::Digits => Some(4),
            TextCombineUpright::Digits2 => Some(2),
            TextCombineUpright::Digits3 => Some(3),
            _ => None,
        }
    }
}

/// Emphasis mark (圏点 / bouten) applied per span, mirroring CSS
/// `text-emphasis-style`. The mark is drawn above each eligible horizontal
/// base character or to the right of its vertical column.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextEmphasis {
    #[default]
    None,
    FilledDot,
    OpenDot,
    FilledCircle,
    OpenCircle,
    FilledSesame,
    OpenSesame,
}

impl TextEmphasis {
    pub fn is_none(self) -> bool {
        matches!(self, TextEmphasis::None)
    }

    /// The glyph drawn as the emphasis mark, following the CSS
    /// `text-emphasis-style` character mapping.
    pub fn mark_char(self) -> Option<char> {
        match self {
            TextEmphasis::None => None,
            TextEmphasis::FilledDot => Some('•'),
            TextEmphasis::OpenDot => Some('◦'),
            TextEmphasis::FilledCircle => Some('●'),
            TextEmphasis::OpenCircle => Some('○'),
            TextEmphasis::FilledSesame => Some('﹅'),
            TextEmphasis::OpenSesame => Some('﹆'),
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum FontFeatures {
    #[default]
    None,
    Palt,
    Vpal,
}

/// Controls whether annotation layers participate in line/column spacing.
/// The default preserves legacy documents; `Auto` reserves one half-em for
/// each active ruby or emphasis layer.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum AnnotationClearance {
    #[default]
    None,
    Auto,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubySize {
    #[default]
    Half,
    Third,
    Quarter,
}

impl RubySize {
    pub fn scale(self) -> f32 {
        match self {
            Self::Half => 0.5,
            Self::Third => 1.0 / 3.0,
            Self::Quarter => 0.25,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyAlign {
    #[default]
    SpaceAround,
    Center,
    Start,
    SpaceBetween,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyOverhang {
    #[default]
    Auto,
    None,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubySide {
    #[default]
    Over,
    Under,
}

impl AnnotationClearance {
    pub fn is_auto(self) -> bool {
        matches!(self, AnnotationClearance::Auto)
    }
}

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

    /// Span texts as fed to the paragraph builders: text-transform applied,
    /// Japanese spacing normalized, and kinsoku break suppressions inserted,
    /// plus the map between original and builder-text UTF-16 offsets. Every
    /// consumer of laid-out offsets must translate through the map. The layout
    /// transform is skipped under letter-spacing, where skparagraph would add
    /// letter spacing to synthetic layout characters.
    pub fn layout_span_texts(&self) -> (Vec<String>, kinsoku::OffsetMap) {
        let texts: Vec<String> = self
            .children
            .iter()
            .map(|s| s.apply_text_transform())
            .collect();
        let has_letter_spacing =
            self.letter_spacing != 0.0 || self.children.iter().any(|s| s.letter_spacing != 0.0);
        if !has_letter_spacing {
            let ruby_breaks: Vec<Option<Vec<usize>>> = self
                .children
                .iter()
                .zip(&texts)
                .map(|(span, _text)| (!span.ruby.trim().is_empty()).then(Vec::new))
                .collect();
            if let Some((shifted, map)) =
                kinsoku::apply_to_span_texts_with_ruby_breaks(&texts, &ruby_breaks)
            {
                return (shifted, map);
            }
        }
        (texts, kinsoku::OffsetMap::default())
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
#[cfg(test)]
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
#[cfg(test)]
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

/// Text after browser filtering and CSS text transformation, plus the source
/// UTF-16 range that produced each transformed Unicode scalar. A single source
/// scalar can produce several output scalars (for example `ß` uppercases to
/// `SS`); keeping that ownership lets vertical layout wrap and export the
/// transformed glyphs as one source-text unit.
#[derive(Debug, Clone, PartialEq)]
pub struct AppliedTextTransform {
    pub text: String,
    source_ranges: Vec<(std::ops::Range<usize>, std::ops::Range<usize>)>,
}

impl AppliedTextTransform {
    pub fn source_utf16_range(
        &self,
        transformed: std::ops::Range<usize>,
    ) -> std::ops::Range<usize> {
        let mut ranges = self.source_ranges.iter().filter_map(|(output, source)| {
            (output.start < transformed.end && output.end > transformed.start)
                .then_some(source.clone())
        });
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

        let processed = if source_char == '\n'
            || source_char == '\r'
            || source_char == '\u{2028}'
            || source_char == '\u{2029}'
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
    /// Warichu (割注): render the span as two half-size lines stacked inline
    /// within one column position of the vertical flow.
    pub warichu: bool,
    pub font_features: FontFeatures,
    pub annotation_clearance: AnnotationClearance,
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
            font_weight,
            font_variant_id,
            fills,
        }
    }

    pub fn set_text(&mut self, text: String) {
        self.text = text;
    }

    pub fn set_ruby(&mut self, ruby: String) {
        self.ruby = ruby;
    }

    pub fn set_ruby_size(&mut self, value: RubySize) {
        self.ruby_size = value;
    }

    pub fn set_ruby_align(&mut self, value: RubyAlign) {
        self.ruby_align = value;
    }

    pub fn set_ruby_overhang(&mut self, value: RubyOverhang) {
        self.ruby_overhang = value;
    }

    pub fn set_ruby_side(&mut self, value: RubySide) {
        self.ruby_side = value;
    }

    pub fn set_text_orientation(&mut self, text_orientation: TextOrientation) {
        self.text_orientation = text_orientation;
    }

    pub fn set_text_combine_upright(&mut self, text_combine_upright: TextCombineUpright) {
        self.text_combine_upright = text_combine_upright;
    }

    pub fn set_text_emphasis(&mut self, text_emphasis: TextEmphasis) {
        self.text_emphasis = text_emphasis;
    }

    pub fn set_warichu(&mut self, warichu: bool) {
        self.warichu = warichu;
    }

    pub fn set_font_features(&mut self, font_features: FontFeatures) {
        self.font_features = font_features;
    }

    pub fn set_annotation_clearance(&mut self, clearance: AnnotationClearance) {
        self.annotation_clearance = clearance;
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

        let annotation_layers = if self.annotation_clearance.is_auto() {
            usize::from(!self.ruby.trim().is_empty()) + usize::from(!self.text_emphasis.is_none())
        } else {
            0
        };
        let max_line_height =
            f32::max(paragraph_line_height, self.line_height) + annotation_layers as f32 * 0.5;
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

    pub fn apply_text_transform(&self) -> String {
        self.apply_text_transform_with_source_ranges().text
    }

    pub fn apply_text_transform_with_source_ranges(&self) -> AppliedTextTransform {
        let browser = crate::with_state!(state, { state.current_browser });
        apply_text_transform_with_source_ranges(&self.text, browser, self.text_transform)
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
    let vertical_offset = match shape.vertical_align() {
        VerticalAlign::Center => (selrect_height - total_text_height) / 2.0,
        VerticalAlign::Bottom => selrect_height - total_text_height,
        _ => 0.0,
    };
    let mut paragraph_layouts: Vec<ParagraphLayout> = Vec::new();
    let mut y_accum = base_y + vertical_offset;
    for (i, group_paragraphs) in built_groups.into_iter().enumerate() {
        // For each paragraph in the group (e.g., fill, stroke, etc.)
        for skia_paragraph in group_paragraphs.into_iter() {
            // Calculate text decorations for this paragraph
            let mut decorations = Vec::new();
            let line_metrics = skia_paragraph.get_line_metrics();
            for line in &line_metrics {
                let style_metrics: Vec<_> = line
                    .get_style_metrics(line.start_index..line.end_index)
                    .into_iter()
                    .collect();
                let line_baseline = y_accum + line.baseline as f32;
                let (max_underline_thickness, underline_y, max_strike_thickness, strike_y) =
                    calculate_decoration_metrics(&style_metrics, line_baseline);
                for (i, (style_start, style_metric)) in style_metrics.iter().enumerate() {
                    let text_style = &style_metric.text_style;
                    let style_end = style_metrics
                        .get(i + 1)
                        .map(|(next_i, _)| *next_i)
                        .unwrap_or(line.end_index);
                    let seg_start = (*style_start).max(line.start_index);
                    let seg_end = style_end.min(line.end_index);
                    if seg_start >= seg_end {
                        continue;
                    }
                    let rects = skia_paragraph.get_rects_for_range(
                        seg_start..seg_end,
                        skia::textlayout::RectHeightStyle::Tight,
                        skia::textlayout::RectWidthStyle::Tight,
                    );
                    let (segment_width, actual_x_offset) = if !rects.is_empty() {
                        let total_width: f32 = rects.iter().map(|r| r.rect.width()).sum();
                        let skia_x_offset = rects
                            .first()
                            .map(|r| r.rect.left - line.left as f32)
                            .unwrap_or(0.0);
                        (total_width, skia_x_offset)
                    } else {
                        (0.0, 0.0)
                    };
                    let text_left = x + line.left as f32 + actual_x_offset;
                    let text_width = segment_width;
                    use skia::textlayout::TextDecoration;
                    if text_style.decoration().ty == TextDecoration::UNDERLINE {
                        decorations.push(TextDecorationSegment {
                            kind: TextDecoration::UNDERLINE,
                            text_style: (*text_style).clone(),
                            y: underline_y.unwrap_or(line_baseline),
                            thickness: max_underline_thickness,
                            left: text_left,
                            width: text_width,
                        });
                    }
                    if text_style.decoration().ty == TextDecoration::LINE_THROUGH {
                        decorations.push(TextDecorationSegment {
                            kind: TextDecoration::LINE_THROUGH,
                            text_style: (*text_style).clone(),
                            y: strike_y.unwrap_or(line_baseline),
                            thickness: max_strike_thickness,
                            left: text_left,
                            width: text_width,
                        });
                    }
                }
            }
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
            let current_y = para_layout.y;
            let text_paragraph = text_paragraphs.get(paragraph_index);
            if let Some(text_para) = text_paragraph {
                // Ranges are in the builder-text (kinsoku-shifted)
                // space; exported positions are translated back to
                // original span-relative offsets through the map.
                let (_, offset_map) = text_para.layout_span_texts();
                let span_ranges = horizontal_span_ranges(text_para);
                let placeholder_rects = para_layout.paragraph.get_rects_for_placeholders();
                let mut placeholder_index = 0usize;
                for range in span_ranges {
                    if range.warichu {
                        if let Some(textbox) = placeholder_rects.get(placeholder_index) {
                            let mut rect = textbox.rect;
                            rect.offset((x, current_y));
                            position_data.push(PositionData {
                                paragraph: paragraph_index as u32,
                                span: range.span as u32,
                                start_pos: 0,
                                end_pos: (range.source_end - range.source_start) as u32,
                                x: rect.x(),
                                y: rect.y(),
                                width: rect.width(),
                                height: rect.height(),
                                direction: direction_to_int(TextDirection::LTR),
                            });
                        }
                        placeholder_index += 1;
                        continue;
                    }
                    let orig_span_start = range.source_start;
                    let rects = para_layout.paragraph.get_rects_for_range(
                        range.builder_start..range.builder_end,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    );

                    for textbox in rects {
                        let direction = textbox.direct;
                        let mut rect = textbox.rect;
                        let cy = rect.top + rect.height() / 2.0;

                        // Get byte positions from Skia's transformed text layout
                        let to_source = |builder_position: usize| {
                            let within = builder_position
                                .saturating_sub(range.builder_start)
                                .min(range.builder_end - range.builder_start);
                            offset_map.to_original(range.shifted_start + within)
                        };
                        let start_pos = to_source(
                            para_layout
                                .paragraph
                                .get_glyph_position_at_coordinate((rect.left + 0.1, cy))
                                .position as usize,
                        ) - orig_span_start;

                        let end_pos = to_source(
                            para_layout
                                .paragraph
                                .get_glyph_position_at_coordinate((rect.right - 0.1, cy))
                                .position as usize,
                        ) - orig_span_start;

                        rect.offset((x, current_y));
                        position_data.push(PositionData {
                            paragraph: paragraph_index as u32,
                            span: range.span as u32,
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

    // Vertical writing generates position data from the vertical cells.
    if text_content.is_vertical() {
        if skip_position_data {
            return Vec::new();
        }
        let max_height = super::text_vertical::wrap_height(&text_content, shape.selrect.height());
        let layout = super::text_vertical::layout_from_content(&text_content, max_height);
        return super::text_vertical::position_data(
            &layout,
            &shape.selrect,
            shape.vertical_align(),
        );
    }

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

    // apply_text_transform reads the browser from the design state.
    fn init_state() {
        crate::globals::design_init();
    }

    fn make_span(text: &str, letter_spacing: f32) -> TextSpan {
        TextSpan {
            text: text.to_string(),
            font_family: FontFamily::new(Uuid::nil(), 400, shapes::FontStyle::Normal),
            font_size: 16.0,
            line_height: 1.0,
            letter_spacing,
            font_weight: 400,
            font_variant_id: Uuid::nil(),
            text_decoration: None,
            text_transform: None,
            text_direction: TextDirection::LTR,
            text_orientation: TextOrientation::default(),
            text_combine_upright: TextCombineUpright::default(),
            text_emphasis: TextEmphasis::default(),
            ruby: String::default(),
            warichu: false,
            font_features: FontFeatures::default(),
            annotation_clearance: AnnotationClearance::default(),
            ruby_size: RubySize::default(),
            ruby_align: RubyAlign::default(),
            ruby_overhang: RubyOverhang::default(),
            ruby_side: RubySide::default(),
            fills: vec![],
        }
    }

    fn make_paragraph(spans: Vec<TextSpan>, letter_spacing: f32) -> Paragraph {
        Paragraph::new(
            TextAlign::default(),
            TextDirection::LTR,
            None,
            None,
            1.0,
            letter_spacing,
            spans,
        )
    }

    #[test]
    fn layout_span_texts_applies_kinsoku() {
        init_state();
        let paragraph = make_paragraph(vec![make_span("雪国", 0.0), make_span("。です", 0.0)], 0.0);
        let (texts, map) = paragraph.layout_span_texts();
        assert_eq!(
            texts,
            vec!["雪国".to_string(), "\u{2060}。です".to_string()]
        );
        assert!(!map.is_empty());
        assert_eq!(map.to_original(3), 2);
    }

    #[test]
    fn layout_span_texts_skips_kinsoku_under_paragraph_letter_spacing() {
        init_state();
        let paragraph = make_paragraph(vec![make_span("雪国。", 0.0)], 2.0);
        let (texts, map) = paragraph.layout_span_texts();
        assert_eq!(texts, vec!["雪国。".to_string()]);
        assert!(map.is_empty());
    }

    #[test]
    fn layout_span_texts_skips_kinsoku_under_span_letter_spacing() {
        init_state();
        let paragraph = make_paragraph(vec![make_span("雪国。", 1.5)], 0.0);
        let (texts, map) = paragraph.layout_span_texts();
        assert_eq!(texts, vec!["雪国。".to_string()]);
        assert!(map.is_empty());
    }

    #[test]
    fn layout_span_texts_respects_text_transform() {
        init_state();
        let mut span = make_span("hello。", 0.0);
        span.text_transform = Some(TextTransform::Uppercase);
        let paragraph = make_paragraph(vec![span], 0.0);
        let (texts, _) = paragraph.layout_span_texts();
        assert_eq!(texts, vec!["HELLO\u{2060}。".to_string()]);
    }

    #[test]
    fn layout_span_texts_identity_map_for_plain_text() {
        init_state();
        let paragraph = make_paragraph(vec![make_span("helloworld", 0.0)], 0.0);
        let (texts, map) = paragraph.layout_span_texts();
        assert_eq!(texts, vec!["helloworld".to_string()]);
        assert!(map.is_empty());
        assert_eq!(map.to_original(5), 5);
        assert_eq!(map.to_shifted(5), 5);
    }

    #[test]
    fn horizontal_ruby_is_atomic() {
        init_state();
        let mut group = make_span("日本", 0.0);
        group.ruby = "にほん".to_string();
        let paragraph = make_paragraph(vec![group], 0.0);
        assert_eq!(
            paragraph.layout_span_texts().0,
            vec!["日\u{2060}本".to_string()]
        );
    }

    #[test]
    fn horizontal_warichu_collapses_to_one_builder_position() {
        init_state();
        let mut warichu = make_span("割注入り", 0.0);
        warichu.warichu = true;
        let paragraph = make_paragraph(vec![warichu, make_span("後", 0.0)], 0.0);

        let ranges = horizontal_span_ranges(&paragraph);
        assert_eq!(ranges[0].builder_start..ranges[0].builder_end, 0..3);
        assert_eq!(ranges[1].builder_start..ranges[1].builder_end, 3..4);
        assert_eq!(horizontal_source_to_builder(&paragraph, 2), 0);
        assert_eq!(horizontal_source_to_builder(&paragraph, 4), 3);
        assert_eq!(horizontal_source_to_builder(&paragraph, 5), 4);
        assert_eq!(horizontal_builder_to_source(&paragraph, 1), 4);
        assert_eq!(horizontal_builder_to_source(&paragraph, 2), 4);
        assert_eq!(horizontal_builder_to_source(&paragraph, 3), 4);
        assert_eq!(horizontal_builder_to_source(&paragraph, 4), 5);
        assert_eq!(
            horizontal_normal_selection_ranges(&paragraph, 1, 5),
            vec![3..4]
        );
    }

    #[test]
    fn horizontal_builder_mapping_preserves_non_bmp_boundaries() {
        init_state();
        let paragraph = make_paragraph(vec![make_span("😀A", 0.0)], 0.0);

        assert_eq!(horizontal_source_to_builder(&paragraph, 1), 2);
        assert_eq!(horizontal_builder_to_source(&paragraph, 2), 1);
        assert_eq!(horizontal_source_to_builder(&paragraph, 2), 3);
        assert_eq!(horizontal_builder_to_source(&paragraph, 3), 2);
    }

    #[test]
    fn horizontal_warichu_builder_emits_one_styled_placeholder() {
        init_state();
        let mut span = make_span("割注入り", 0.0);
        span.warichu = true;
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(span.font_size);
        let mut fonts = skia::textlayout::FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        builder.push_style(&style);
        add_horizontal_span(&mut builder, &span, &span.text, &style, &fonts);
        let mut laid_out = builder.build();
        laid_out.layout(200.0);

        let placeholders = laid_out.get_rects_for_placeholders();
        assert_eq!(placeholders.len(), 1);
        assert!(placeholders[0].rect.width() > 0.0);
        assert!(placeholders[0].rect.height() > 0.0);
        let has_style = laid_out
            .get_line_metrics()
            .iter()
            .any(|line| !line.get_style_metrics(3..5).is_empty());
        assert!(
            has_style,
            "the paint pass must recover the placeholder style"
        );
    }

    #[test]
    fn horizontal_warichu_allows_wrapping_after_the_atomic_box() {
        init_state();
        let mut span = make_span("割注入り", 0.0);
        span.warichu = true;
        let following = make_span("A", 0.0);
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(span.font_size);
        let mut fonts = skia::textlayout::FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        builder.push_style(&style);
        add_horizontal_span(&mut builder, &span, &span.text, &style, &fonts);
        builder.push_style(&style);
        builder.add_text(&following.text);

        let mut laid_out = builder.build();
        laid_out.layout(16.1);

        assert_eq!(laid_out.get_rects_for_placeholders().len(), 1);
        assert_eq!(laid_out.get_line_metrics().len(), 2);
    }

    #[test]
    fn emphasis_excludes_whitespace_and_japanese_punctuation() {
        for character in " \t\n、。，．「」『』（）［］【】〔〕〈〉《》‘’“”".chars()
        {
            assert!(
                !emphasis_char_allowed(character),
                "emphasis must skip {character:?}"
            );
        }
        for character in "漢あA1・！？".chars() {
            assert!(
                emphasis_char_allowed(character),
                "emphasis should mark {character:?}"
            );
        }
    }

    #[test]
    fn horizontal_emphasis_tracks_eligible_unicode_characters() {
        init_state();
        let mut span = make_span("A😀。 B", 0.0);
        span.text_emphasis = TextEmphasis::FilledDot;
        let paragraph = make_paragraph(vec![span], 0.0);
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(16.0);
        let mut fonts = skia::textlayout::FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        let (texts, _) = paragraph.layout_span_texts();
        for (span, text) in paragraph.children().iter().zip(texts) {
            builder.push_style(&style);
            add_horizontal_span(&mut builder, span, &text, &style, &fonts);
        }
        let mut laid_out = builder.build();
        laid_out.layout(200.0);

        let placements = horizontal_emphasis_placements(&paragraph, &laid_out);
        assert_eq!(placements.len(), 3, "A, emoji and B receive one mark each");
        assert!(placements
            .iter()
            .all(|placement| placement.rect.width() > 0.0));
        assert!(horizontal_span_style(&laid_out, &horizontal_span_ranges(&paragraph)[0]).is_some());
    }

    #[test]
    fn horizontal_emphasis_recovers_each_non_ascii_span_style() {
        init_state();
        let mut first = make_span("漢", 0.0);
        first.text_emphasis = TextEmphasis::FilledDot;
        let mut second = make_span("字", 0.0);
        second.text_emphasis = TextEmphasis::OpenCircle;
        let paragraph = make_paragraph(vec![first, second], 0.0);
        let mut fonts = skia::textlayout::FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        let (texts, _) = paragraph.layout_span_texts();
        for (index, (span, text)) in paragraph.children().iter().zip(texts).enumerate() {
            let mut style = skia::textlayout::TextStyle::default();
            style.set_font_size(if index == 0 { 16.0 } else { 24.0 });
            builder.push_style(&style);
            add_horizontal_span(&mut builder, span, &text, &style, &fonts);
        }
        let mut laid_out = builder.build();
        laid_out.layout(200.0);

        let ranges = horizontal_span_ranges(&paragraph);
        assert_eq!(
            horizontal_span_style(&laid_out, &ranges[0])
                .unwrap()
                .font_size(),
            16.0
        );
        assert_eq!(
            horizontal_span_style(&laid_out, &ranges[1])
                .unwrap()
                .font_size(),
            24.0
        );
    }
}
