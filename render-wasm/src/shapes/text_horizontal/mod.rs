// Horizontal Japanese layout on top of SkParagraph: the kinsoku layout
// texts, punctuation aki, offset maps between source, layout and builder
// text, and the ruby, emphasis and warichu painters.

mod emphasis;
mod justify;
mod offsets;
mod ruby;
mod warichu;

#[cfg(test)]
mod tests;

pub(crate) use emphasis::*;
pub(crate) use justify::*;
pub(crate) use offsets::*;
pub(crate) use ruby::*;
pub(crate) use warichu::*;

use super::text::{add_text_with_tabs, Paragraph, TextContent, TextSpan};
use super::text_japanese::*;
use super::text_vertical::{
    is_upright_char, shape_segment_with_fallbacks, single_glyph_blob, span_font_families, ShapedRun,
};
use crate::globals::get_resources;
use crate::math::Point;
use crate::shapes::japanese::{
    aki_class, is_japanese_text_char, pair_rule, punctuation_aki_sheds, AkiShed, JapaneseClass,
};
use crate::shapes::{kinsoku, merge_fills};
use crate::utils::{get_fallback_fonts, get_font_collection};
use skia_safe::{
    self as skia,
    textlayout::{
        ParagraphBuilder, ParagraphStyle, PlaceholderAlignment, PlaceholderStyle, RectHeightStyle,
        RectWidthStyle, TextBaseline,
    },
    Canvas, Contains, Font, FontMgr, GlyphId,
};

/// True when a paragraph with layout texts `texts` needs the Japanese passes:
/// it has ruby or a Japanese character.
pub(crate) fn uses_japanese_layout(paragraph: &Paragraph, texts: &[String]) -> bool {
    paragraph.children().iter().any(TextSpan::has_ruby)
        || texts
            .iter()
            .any(|text| text.chars().any(is_japanese_text_char))
}

pub(crate) fn layout_span_texts(paragraph: &Paragraph) -> (Vec<String>, kinsoku::OffsetMap) {
    let texts: Vec<String> = paragraph
        .children()
        .iter()
        .map(TextSpan::apply_text_transform)
        .collect();
    // Text without Japanese characters or ruby keeps plain Skia layout.
    if uses_japanese_layout(paragraph, &texts) {
        let ruby_breaks: Vec<Option<Vec<usize>>> = paragraph
            .children()
            .iter()
            .map(|span| span.has_ruby().then(Vec::new))
            .collect();
        if let Some((shifted, map)) =
            kinsoku::apply_to_span_texts_with_ruby_breaks(&texts, &ruby_breaks)
        {
            return (shifted, map);
        }
    }
    (texts, kinsoku::OffsetMap::default())
}

/// (char index, amount) per span of the layout `texts` for the characters
/// whose advance loses a half-em to `punctuation_aki_sheds`: a closing mark
/// sheds half of its own em, and the character before an opening bracket
/// gives up half of the bracket's em. Inserted word joiners are transparent;
/// `palt` spans set punctuation proportionally.
pub(crate) fn horizontal_aki_sheds(
    paragraph: &Paragraph,
    texts: &[String],
) -> Vec<Vec<(usize, f32)>> {
    let spans = paragraph.children();
    let half_em = |span: usize| spans.get(span).map_or(0.0, |span| span.font_size * 0.5);
    // (span, char index in the span text) of every real character.
    let mut chars: Vec<(usize, usize)> = Vec::new();
    let mut classes: Vec<Option<JapaneseClass>> = Vec::new();
    for (span_index, text) in texts.iter().enumerate() {
        let span = spans.get(span_index);
        let ruby_base = span.is_some_and(TextSpan::has_ruby);
        let proportional = span.is_some_and(|span| span.font_features == FontFeatures::Palt);
        for (index, ch) in text.chars().enumerate() {
            if kinsoku::is_inserted_zero_width(ch) {
                continue;
            }
            chars.push((span_index, index));
            classes.push(aki_class(ch, ruby_base, proportional));
        }
    }
    let mut sheds: Vec<Vec<(usize, f32)>> = vec![Vec::new(); texts.len()];
    for (at, shed) in punctuation_aki_sheds(&classes) {
        let (span, amount_span, index) = match shed {
            AkiShed::Trailing => (chars[at].0, chars[at].0, chars[at].1),
            AkiShed::Leading => (chars[at - 1].0, chars[at].0, chars[at - 1].1),
        };
        sheds[span].push((index, half_em(amount_span)));
    }
    sheds
}

/// Add a span to a horizontal paragraph builder. A warichu span becomes one
/// inline placeholder so its two lines wrap as a unit; its glyphs are painted
/// after layout. The characters at `sheds` (from `horizontal_aki_sheds`)
/// set narrower by their amounts; `extra` (from `horizontal_ruby_spacing`)
/// adds letter-spacing to characters.
pub(crate) fn add_horizontal_span(
    builder: &mut ParagraphBuilder,
    span: &TextSpan,
    builder_text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) {
    if span.is_warichu() {
        let text = span.apply_text_transform();
        let (first, second) = warichu_text_lines(&text);
        let mut mini_style = text_style.clone();
        mini_style.set_font_size(span.font_size * WARICHU_FONT_SCALE);
        mini_style.set_height(1.0);
        mini_style.set_height_override(true);
        mini_style.set_letter_spacing(span.letter_spacing * WARICHU_FONT_SCALE);
        let measure = |line: &str| mini_paragraph(line, &mini_style, fonts).longest_line();
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
        // metrics. A near-zero, inkless NBSP carries the span style for the
        // paint pass; the zero-width space after it allows a line break after
        // the atomic placeholder.
        let mut anchor_style = text_style.clone();
        anchor_style.set_font_size(0.01);
        anchor_style.set_height(0.01);
        anchor_style.set_height_override(true);
        anchor_style.set_letter_spacing(0.0);
        builder.push_style(&anchor_style);
        builder.add_text(HORIZONTAL_WARICHU_STYLE_ANCHOR.to_string());
        builder.add_text(HORIZONTAL_WARICHU_BREAK_ANCHOR.to_string());
    } else {
        add_text_with_sheds(builder, span, builder_text, sheds, extra, text_style);
    }
}

/// Add `text`, adjusting the letter-spacing of single characters so the
/// builder text stays unchanged: those at `sheds` lose their amount and
/// those in `extra` take its added spacing. Skia tracks every cluster,
/// including the zero-width word joiners the kinsoku pass inserts, so those
/// take no letter-spacing.
fn add_text_with_sheds(
    builder: &mut ParagraphBuilder,
    span: &TextSpan,
    text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
) {
    let tracking = text_style.letter_spacing();
    let mut piece_start = 0;
    for (index, (byte, ch)) in text.char_indices().enumerate() {
        let amount_at = |adjustments: &[(usize, f32)]| -> f32 {
            adjustments
                .iter()
                .filter(|(char_index, _)| *char_index == index)
                .map(|(_, amount)| amount)
                .sum()
        };
        let added = amount_at(extra);
        let shed = amount_at(sheds);
        let letter_spacing = if kinsoku::is_inserted_zero_width(ch) {
            0.0
        } else {
            tracking + added - shed
        };
        if letter_spacing == tracking {
            continue;
        }
        let mut style = text_style.clone();
        style.set_letter_spacing(letter_spacing);
        let end = byte + ch.len_utf8();
        add_text_with_tabs(builder, &text[piece_start..byte], span.font_size);
        builder.push_style(&style);
        builder.add_text(&text[byte..end]);
        builder.pop();
        piece_start = end;
    }
    add_text_with_tabs(builder, &text[piece_start..], span.font_size);
}

/// The horizontal passes of one paragraph that layout, painting, position
/// data and the editor share: kinsoku layout texts, offsets, punctuation aki
/// sheds and long-ruby spacing. `TextContent::horizontal_plans` caches them.
pub(crate) struct HorizontalParagraphPlan {
    pub(crate) texts: Vec<String>,
    pub(crate) offsets: HorizontalOffsets,
    pub(crate) sheds: Vec<Vec<(usize, f32)>>,
    pub(crate) ruby_spacing: HorizontalRubySpacing,
}

impl HorizontalParagraphPlan {
    pub(crate) fn new(paragraph: &Paragraph) -> Self {
        let (texts, offset_map) = paragraph.layout_span_texts();
        let sheds = horizontal_aki_sheds(paragraph, &texts);
        let ruby_spacing = horizontal_ruby_spacing(paragraph, &texts, &offset_map);
        let offsets = HorizontalOffsets::from_layout_texts(paragraph, &texts, offset_map);
        Self {
            texts,
            offsets,
            sheds,
            ruby_spacing,
        }
    }
}

/// Single-line paragraph for an annotation run. Laid out unbounded: a
/// re-layout at its own `longest_line()` can wrap the last glyph.
fn mini_paragraph(
    text: &str,
    style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) -> skia::textlayout::Paragraph {
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), fonts);
    builder.push_style(style);
    builder.add_text(text);
    let mut paragraph = builder.build();
    paragraph.layout(f32::MAX);
    paragraph
}

pub(crate) fn horizontal_span_style(
    laid_out: &skia::textlayout::Paragraph,
    range: &HorizontalSpanRange,
) -> Option<skia::textlayout::TextStyle> {
    // Indexed style metrics use builder UTF-8 byte positions, unlike the
    // UTF-16 offsets of glyph and line ranges.
    let anchor = range.style_anchor_start..range.style_anchor_start + 1;
    laid_out.get_line_metrics().iter().find_map(|line| {
        line.get_style_metrics(anchor.clone())
            .into_iter()
            .next()
            .map(|(_, metric)| metric.text_style.clone())
    })
}

/// Top of the horizontal base em inside SkParagraph's typographic rect, less
/// a 3/14-em clearance (12 px at 56 px). The tight rect includes ascender-side
/// padding, so anchoring an over annotation to its top leaves a gap above CJK
/// ink; the em is bottom-aligned to the rect.
pub(crate) fn horizontal_annotation_over_top(rect: skia::Rect, font_size: f32) -> f32 {
    let font_size = font_size.max(0.0);
    rect.top.max(rect.bottom - font_size) - font_size * (3.0 / 14.0)
}
