use crate::shapes::text_paragraph::{LayoutParagraph, SpacedParagraphBuilder};
// Horizontal Japanese layout on top of SkParagraph: the kinsoku layout
// texts, punctuation aki, offset maps between source, layout and builder
// text, and the ruby, emphasis and warichu painters.

mod emphasis;
mod justify;
mod offsets;
mod overflow;
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
use crate::shapes::text_policy::TextLayoutPolicy;
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
    paragraph.layout_policy() == TextLayoutPolicy::Japanese
        && (paragraph.children().iter().any(TextSpan::has_ruby)
            || texts
                .iter()
                .any(|text| text.chars().any(is_japanese_text_char)))
}

#[cfg(test)]
pub(crate) fn layout_span_texts(paragraph: &Paragraph) -> (Vec<String>, kinsoku::OffsetMap) {
    layout_span_texts_with_breaks(paragraph, &[], &[])
}

fn layout_span_texts_with_breaks(
    paragraph: &Paragraph,
    emergency: &[usize],
    forced: &[usize],
) -> (Vec<String>, kinsoku::OffsetMap) {
    let texts: Vec<String> = paragraph
        .children()
        .iter()
        .map(TextSpan::apply_text_transform)
        .collect();
    // Text without Japanese characters or ruby keeps plain Skia layout.
    let policy = paragraph.layout_policy();
    if uses_japanese_layout(paragraph, &texts) || policy.is_chinese() || policy.is_korean() {
        let ruby_breaks: Vec<Option<Vec<usize>>> = paragraph
            .children()
            .iter()
            .map(|span| span.has_ruby().then(Vec::new))
            .collect();
        let shifted =
            kinsoku::apply_policy_with_breaks(&texts, &ruby_breaks, policy, emergency, forced);
        if let Some((shifted, map)) = shifted {
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
    if paragraph.layout_policy() != TextLayoutPolicy::Japanese {
        return horizontal_policy_spacing(paragraph, texts);
    }
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

/// Signed advance changes: positive removes punctuation space, negative adds
/// the regional Han/Latin gap. Source and builder text stay unchanged.
fn horizontal_policy_spacing(paragraph: &Paragraph, texts: &[String]) -> Vec<Vec<(usize, f32)>> {
    let mut result = vec![Vec::new(); texts.len()];
    let policy = paragraph.layout_policy();
    let mut previous: Option<(usize, usize, char)> = None;
    for (span, text) in texts.iter().enumerate() {
        for (index, ch) in text.chars().enumerate() {
            if kinsoku::is_inserted_zero_width(ch) {
                continue;
            }
            if let Some((before_span, before_index, before)) = previous {
                let a = &paragraph.children()[before_span];
                let b = &paragraph.children()[span];
                if !a.has_ruby() && !b.has_ruby() && !a.is_warichu() && !b.is_warichu() {
                    let em = a.font_size.min(b.font_size);
                    let compression = if a.font_features == FontFeatures::Palt
                        || b.font_features == FontFeatures::Palt
                    {
                        0.0
                    } else {
                        policy.compression_em(before, ch, false)
                    };
                    let amount = (compression - policy.script_spacing_em(before, ch)) * em;
                    if amount != 0.0 {
                        result[before_span].push((before_index, amount));
                    }
                }
            }
            previous = Some((span, index, ch));
        }
    }
    result
}

/// Add a span to a horizontal paragraph builder. A warichu span becomes one
/// inline placeholder so its two lines wrap as a unit; its glyphs are painted
/// after layout. The characters at `sheds` (from `horizontal_aki_sheds`)
/// set narrower by their amounts; `extra` (from `horizontal_ruby_spacing`)
/// adds letter-spacing to characters.
pub(crate) fn add_horizontal_span(
    builder: &mut impl SpacedParagraphBuilder,
    span: &TextSpan,
    builder_text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) {
    if span.is_warichu() {
        let builder = builder.paragraph_builder();
        if builder_text.starts_with(kinsoku::FORCED_LINE_BREAK) {
            builder.add_text(kinsoku::FORCED_LINE_BREAK.to_string());
        }
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
/// take no letter-spacing. The builder records how far each adjusted
/// character departs from the span tracking.
fn add_text_with_sheds(
    builder: &mut impl SpacedParagraphBuilder,
    span: &TextSpan,
    text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
) {
    let tracking = text_style.letter_spacing();
    let count = text.chars().count();
    let mut amounts = vec![(0.0, 0.0); count];
    for &(index, amount) in extra {
        if let Some((added, _)) = amounts.get_mut(index) {
            *added += amount;
        }
    }
    for &(index, amount) in sheds {
        if let Some((_, shed)) = amounts.get_mut(index) {
            *shed += amount;
        }
    }
    let mut piece_start = 0;
    // UTF-16 offset of the span text in the builder, read at the first piece.
    let mut builder_start = None;
    let mut utf16 = 0;
    for (index, (byte, ch)) in text.char_indices().enumerate() {
        let at = utf16;
        utf16 += ch.len_utf16();
        let (added, shed) = amounts[index];
        let letter_spacing = if kinsoku::is_inserted_zero_width(ch) {
            0.0
        } else {
            tracking + added - shed
        };
        if letter_spacing == tracking {
            continue;
        }
        let start = *builder_start.get_or_insert_with(|| {
            builder
                .paragraph_builder()
                .get_text()
                .encode_utf16()
                .count()
        });
        builder.add_extra_spacing(start + at, letter_spacing - tracking);
        let mut style = text_style.clone();
        style.set_letter_spacing(letter_spacing);
        let end = byte + ch.len_utf8();
        let paragraph_builder = builder.paragraph_builder();
        add_text_with_tabs(paragraph_builder, &text[piece_start..byte], span.font_size);
        paragraph_builder.push_style(&style);
        paragraph_builder.add_text(&text[byte..end]);
        paragraph_builder.pop();
        piece_start = end;
    }
    add_text_with_tabs(
        builder.paragraph_builder(),
        &text[piece_start..],
        span.font_size,
    );
}

/// The horizontal passes of one paragraph that layout, painting, position
/// data and the editor share: kinsoku layout texts, offsets, punctuation aki
/// sheds and long-ruby spacing. `TextContent::horizontal_plans` caches them.
pub(crate) struct HorizontalParagraphPlan {
    pub(crate) texts: Vec<String>,
    pub(crate) offsets: HorizontalOffsets,
    pub(crate) sheds: Vec<Vec<(usize, f32)>>,
    pub(crate) ruby_spacing: HorizontalRubySpacing,
    emergency_breaks: Vec<usize>,
    overflow_width: Option<f32>,
}

impl HorizontalParagraphPlan {
    pub(crate) fn new(paragraph: &Paragraph) -> Self {
        Self::with_emergency_breaks(paragraph, &[])
    }

    /// SkParagraph's emergency fallback can ignore punctuation joins inside
    /// an overlong word. Measure whole units once, then reopen only safe
    /// grapheme boundaries of units that cannot fit an empty line. The
    /// replacement markers have identical UTF-8/UTF-16 lengths, so every
    /// source/builder offset remains identical to the unrelaxed plan.
    pub(crate) fn relax_overlong_words(
        &mut self,
        paragraph: &Paragraph,
        width: f32,
        measure: impl FnOnce(&Self) -> LayoutParagraph,
    ) {
        use unicode_segmentation::UnicodeSegmentation;
        let policy = paragraph.layout_policy();
        if !(policy.is_chinese()
            || matches!(
                policy,
                TextLayoutPolicy::KoreanWords | TextLayoutPolicy::KoreanSyllable
            ))
            || !width.is_finite()
            || width <= 0.0
            || width == f32::MAX
        {
            return;
        }
        let source: String = paragraph
            .children()
            .iter()
            .map(TextSpan::apply_text_transform)
            .collect();
        let mut utf16 = 0;
        let graphemes: Vec<_> = source
            .graphemes(true)
            .map(|g| {
                let at = utf16;
                utf16 += g.encode_utf16().count();
                (at, g.chars().next().unwrap())
            })
            .collect();
        let mut offset = 0;
        let protected: Vec<_> = paragraph
            .children()
            .iter()
            .filter_map(|span| {
                let start = offset;
                offset += span.apply_text_transform().encode_utf16().count();
                (span.has_ruby() || span.is_warichu()).then_some(start..offset)
            })
            .collect();
        let mut units = Vec::new();
        let mut start = 0;
        for at in 1..graphemes.len() {
            if policy.break_allowed(graphemes[at - 1].1, graphemes[at].1) {
                if at - start > 1 {
                    units.push(start..at);
                }
                start = at;
            }
        }
        if graphemes.len() - start > 1 {
            units.push(start..graphemes.len());
        }
        if units.is_empty() {
            return;
        }
        let measured = measure(self);
        let line_starts: Vec<_> = measured
            .get_line_metrics()
            .iter()
            .skip(1)
            .map(|line| line.start_index)
            .collect();
        let mut emergency = Vec::new();
        for unit in units {
            let first = graphemes[unit.start].0;
            let last = graphemes.get(unit.end).map_or(utf16, |g| g.0);
            let from = self.offsets.transformed_to_builder(first);
            let to = self.offsets.transformed_to_builder(last);
            let split = line_starts.partition_point(|at| *at <= from);
            if line_starts.get(split).is_none_or(|at| *at >= to) {
                continue;
            }
            let extent: f32 = measured
                .get_rects_for_range(from..to, RectHeightStyle::Tight, RectWidthStyle::Tight)
                .iter()
                .map(|box_| box_.rect.width())
                .sum();
            if extent <= width + 0.01 {
                continue;
            }
            for at in unit.start + 1..unit.end {
                let (position, after) = graphemes[at];
                let before = graphemes[at - 1].1;
                if policy.emergency_break_allowed(before, after)
                    && protected
                        .get(protected.partition_point(|r| r.end <= position))
                        .is_none_or(|r| position <= r.start)
                {
                    emergency.push(position);
                }
            }
        }
        if !emergency.is_empty() {
            *self = Self::with_emergency_breaks(paragraph, &emergency);
        }
    }

    fn with_emergency_breaks(paragraph: &Paragraph, emergency: &[usize]) -> Self {
        Self::with_breaks(paragraph, emergency, &[])
    }

    fn with_breaks(paragraph: &Paragraph, emergency: &[usize], forced: &[usize]) -> Self {
        let (texts, offset_map) = layout_span_texts_with_breaks(paragraph, emergency, forced);
        let sheds = horizontal_aki_sheds(paragraph, &texts);
        let ruby_spacing = horizontal_ruby_spacing(paragraph, &texts, &offset_map);
        let offsets = HorizontalOffsets::from_layout_texts(paragraph, &texts, offset_map);
        Self {
            texts,
            offsets,
            sheds,
            ruby_spacing,
            emergency_breaks: emergency.to_vec(),
            overflow_width: None,
        }
    }
}

/// Single-line paragraph for an annotation run. Laid out unbounded: a
/// re-layout at its own `longest_line()` can wrap the last glyph.
fn mini_paragraph(
    text: &str,
    style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) -> LayoutParagraph {
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), fonts);
    builder.push_style(style);
    builder.add_text(text);
    let mut paragraph = LayoutParagraph::build(&mut builder);
    paragraph.layout(f32::MAX);
    paragraph
}

pub(crate) fn horizontal_span_style(
    laid_out: &LayoutParagraph,
    range: &HorizontalSpanRange,
) -> Option<skia::textlayout::TextStyle> {
    // Lines use UTF-16, while their style entries use UTF-8 run starts.
    // Skia can merge neighboring spans into one run, so an exact lookup at
    // the later span's anchor may be empty. Recover the preceding style on
    // the line containing the base, without carrying a style across lines.
    let lines = laid_out.get_line_metrics();
    let line = lines.iter().find(|line| {
        line.start_index <= range.style_anchor_utf16
            && range.style_anchor_utf16 < line.end_including_newline
    })?;
    line.get_style_metrics(0..range.style_anchor_start.saturating_add(1))
        .last()
        .map(|(_, metric)| metric.text_style.clone())
}

/// Top of the horizontal base em inside SkParagraph's typographic rect, less
/// a 3/14-em clearance (12 px at 56 px). The tight rect includes ascender-side
/// padding, so anchoring an over annotation to its top leaves a gap above CJK
/// ink; the em is bottom-aligned to the rect.
pub(crate) fn horizontal_annotation_over_top(rect: skia::Rect, font_size: f32) -> f32 {
    let font_size = font_size.max(0.0);
    rect.top.max(rect.bottom - font_size) - font_size * (3.0 / 14.0)
}
