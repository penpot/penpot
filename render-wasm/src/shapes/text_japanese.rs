use super::text::{Paragraph, TextSpan};
use crate::math::Point;
use crate::shapes::kinsoku;
use crate::utils::get_font_collection;
use skia_safe::{
    self as skia,
    textlayout::{
        ParagraphBuilder, ParagraphStyle, PlaceholderAlignment, PlaceholderStyle, RectHeightStyle,
        RectWidthStyle, TextBaseline,
    },
    Contains,
};

pub const WARICHU_FONT_SCALE: f32 = 0.5;
pub const EMPHASIS_FONT_SCALE: f32 = 0.5;
const HORIZONTAL_WARICHU_BUILDER_LEN: usize = 3;
const HORIZONTAL_WARICHU_STYLE_ANCHOR: char = '\u{00A0}';
const HORIZONTAL_WARICHU_BREAK_ANCHOR: char = '\u{200B}';

pub(crate) fn layout_span_texts(paragraph: &Paragraph) -> (Vec<String>, kinsoku::OffsetMap) {
    let texts: Vec<String> = paragraph
        .children()
        .iter()
        .map(TextSpan::apply_text_transform)
        .collect();
    let has_letter_spacing = paragraph.letter_spacing() != 0.0
        || paragraph
            .children()
            .iter()
            .any(|span| span.letter_spacing != 0.0);
    if !has_letter_spacing {
        let ruby_breaks: Vec<Option<Vec<usize>>> = paragraph
            .children()
            .iter()
            .map(|span| (!span.ruby.trim().is_empty()).then(Vec::new))
            .collect();
        if let Some((shifted, map)) =
            kinsoku::apply_to_span_texts_with_ruby_breaks(&texts, &ruby_breaks)
        {
            return (shifted, map);
        }
    }
    (texts, kinsoku::OffsetMap::default())
}

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
pub(crate) struct HorizontalEmphasisPlacement {
    pub(crate) span: usize,
    pub(crate) mark: char,
    pub(crate) rect: skia::Rect,
}

/// Locate one horizontal emphasis mark above each eligible source character.
/// SkParagraph owns wrapping and bidi placement; querying each transformed
/// character range keeps the marks attached to the actual laid-out glyphs.
pub(crate) fn horizontal_emphasis_placements(
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

pub(crate) fn horizontal_span_style(
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
