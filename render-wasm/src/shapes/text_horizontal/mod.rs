// Horizontal CJK layout on top of SkParagraph: the policy's layout texts
// (break joiners and spacing characters), punctuation and inter-script
// spacing, offset maps between source, layout and builder text, line
// adjustment, and the ruby, emphasis and warichu painters.

use crate::shapes::text_paragraph::{LayoutParagraph, SpacedParagraphBuilder};

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
use super::text_attrs::*;
use super::text_ruby::*;
use super::text_shaping::{
    is_upright_char, shape_segment_with_fallbacks, single_glyph_blob, span_font_families, ShapedRun,
};
use super::text_warichu::*;
use crate::globals::get_resources;
use crate::math::Point;
use crate::shapes::jlreq::{
    aki_class, is_cjk_text_char, pair_rule, punctuation_aki_sheds, AkiShed, JlreqClass,
};
use crate::shapes::text_policy::{ChineseRegion, TextLayoutPolicy};
use crate::shapes::{line_breaks, merge_fills};
use crate::utils::{get_fallback_fonts, get_font_collection};
use skia_safe::{
    self as skia,
    textlayout::{
        ParagraphBuilder, ParagraphStyle, PlaceholderAlignment, PlaceholderStyle, RectHeightStyle,
        RectWidthStyle, TextBaseline,
    },
    Canvas, Font, FontMgr, GlyphId,
};

/// True when a paragraph with layout texts `texts` needs the Japanese passes:
/// its policy is Japanese and it has ruby or a CJK text character.
pub(crate) fn uses_japanese_layout(paragraph: &Paragraph, texts: &[String]) -> bool {
    paragraph.layout_policy().uses_jlreq()
        && (paragraph.children().iter().any(TextSpan::has_ruby)
            || texts.iter().any(|text| text.chars().any(is_cjk_text_char)))
}

#[cfg(test)]
pub(crate) fn layout_span_texts(paragraph: &Paragraph) -> (Vec<String>, line_breaks::OffsetMap) {
    layout_span_texts_with_breaks(paragraph, &[], &[])
}

fn layout_span_texts_with_breaks(
    paragraph: &Paragraph,
    emergency: &[usize],
    forced: &[usize],
) -> (Vec<String>, line_breaks::OffsetMap) {
    let texts: Vec<String> = paragraph
        .children()
        .iter()
        .map(TextSpan::apply_text_transform)
        .collect();
    // Text without Japanese characters or ruby keeps plain Skia layout.
    let policy = paragraph.layout_policy();
    if policy.plans_breaks() || uses_japanese_layout(paragraph, &texts) {
        let ruby_breaks: Vec<Option<Vec<usize>>> = paragraph
            .children()
            .iter()
            .map(|span| span.has_ruby().then(Vec::new))
            .collect();
        // A base that crosses spans stays whole across their boundaries.
        let group_of = paragraph.ruby_group_of_spans();
        let joined: Vec<bool> = group_of
            .iter()
            .enumerate()
            .map(|(index, group)| index > 0 && group.is_some() && *group == group_of[index - 1])
            .collect();
        let shifted = line_breaks::apply_policy_with_breaks(
            &texts,
            &ruby_breaks,
            &joined,
            policy,
            emergency,
            forced,
        );
        if let Some((shifted, map)) = shifted {
            return with_leading_ruby_spacer(paragraph, shifted, map);
        }
    }
    with_leading_ruby_spacer(paragraph, texts, line_breaks::OffsetMap::default())
}

/// A paragraph that starts with a reading base gets a word joiner before it:
/// a reading longer than its base spreads the base over its slot, and the
/// space before the base must sit on a character before it
/// (`horizontal_ruby_spacing_with`).
fn with_leading_ruby_spacer(
    paragraph: &Paragraph,
    mut texts: Vec<String>,
    map: line_breaks::OffsetMap,
) -> (Vec<String>, line_breaks::OffsetMap) {
    let starts_with_ruby = paragraph
        .children()
        .first()
        .is_some_and(|span| span.has_ruby() && !span.is_zhuyin());
    match texts.first_mut() {
        Some(first) if starts_with_ruby && !first.is_empty() => {
            first.insert(0, line_breaks::WORD_JOINER);
            (texts, map.with_leading_insertion())
        }
        _ => (texts, map),
    }
}

/// Advance changes between adjacent characters of the layout `texts`, as
/// (char index, amount) per span: positive narrows the character, negative
/// widens it. Japanese sheds JLREQ punctuation aki; the other policies use
/// their pairwise punctuation compression and script spacing.
pub(crate) fn horizontal_pair_spacing(
    paragraph: &Paragraph,
    texts: &[String],
) -> Vec<Vec<(usize, f32)>> {
    if !paragraph.layout_policy().uses_jlreq() {
        return horizontal_pairwise_spacing(paragraph, texts);
    }
    jlreq_aki_sheds(paragraph, texts)
}

/// (char index, amount) per span of the layout `texts` for the characters
/// whose advance loses a half-em to `punctuation_aki_sheds`: a closing mark
/// sheds half of its own em, and the character before an opening bracket
/// gives up half of the bracket's em. Inserted word joiners are transparent;
/// `palt` spans set punctuation proportionally.
fn jlreq_aki_sheds(paragraph: &Paragraph, texts: &[String]) -> Vec<Vec<(usize, f32)>> {
    let spans = paragraph.children();
    let half_em = |span: usize| spans.get(span).map_or(0.0, |span| span.font_size * 0.5);
    // (span, char index in the span text) of every real character.
    let mut chars: Vec<(usize, usize)> = Vec::new();
    let mut classes: Vec<Option<JlreqClass>> = Vec::new();
    for (span_index, text) in texts.iter().enumerate() {
        let span = spans.get(span_index);
        let ruby_base = span.is_some_and(TextSpan::has_ruby);
        let proportional = span.is_some_and(|span| span.font_features == FontFeatures::Palt);
        for (index, ch) in text.chars().enumerate() {
            if line_breaks::is_inserted_zero_width(ch) {
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
fn horizontal_pairwise_spacing(paragraph: &Paragraph, texts: &[String]) -> Vec<Vec<(usize, f32)>> {
    horizontal_pairwise_spacing_with(paragraph, texts, draws_stop_centred)
}

/// Whether the font of `span` draws the regional stop `ch` in the centre of
/// its em, as Traditional Chinese faces do, rather than in the left half,
/// as mainland faces do. False when there are no fonts to measure.
fn draws_stop_centred(span: &TextSpan, ch: char) -> bool {
    if !crate::globals::has_render_resources() {
        return false;
    }
    let provider = get_resources().fonts.font_provider();
    draws_stop_centred_with(span, ch, provider, get_fallback_fonts())
}

fn draws_stop_centred_with(
    span: &TextSpan,
    ch: char,
    provider: &skia::textlayout::TypefaceFontProvider,
    fallbacks: &crate::render::FallbackFonts,
) -> bool {
    let runs = shape_segment_with_fallbacks(
        &ch.to_string(),
        span.font_size.max(1.0),
        &span_font_families(span, fallbacks),
        provider,
        false,
        span.font_features,
        &FontMgr::from(provider.clone()),
        &span.language,
    );
    let [run] = runs.as_slice() else {
        return false;
    };
    let count = run.glyphs.len();
    let advance = run.cluster_advance(0, count);
    // A left-half mark centres its ink near a quarter of the em.
    advance > 0.0
        && run
            .cluster_ink_bounds(0, count)
            .is_some_and(|ink| ink.center_x() > advance * 0.375)
}

/// `horizontal_pairwise_spacing` with `centred` telling whether a span's
/// font draws a regional stop in the centre of its em. Only mainland
/// spacing asks, once per span and mark.
fn horizontal_pairwise_spacing_with(
    paragraph: &Paragraph,
    texts: &[String],
    centred: impl Fn(&TextSpan, char) -> bool,
) -> Vec<Vec<(usize, f32)>> {
    let mut result = vec![Vec::new(); texts.len()];
    let policy = paragraph.layout_policy();
    let asks = policy == TextLayoutPolicy::Chinese(ChineseRegion::Mainland);
    let mut measured: Vec<(usize, char, bool)> = Vec::new();
    let mut is_centred = |span: usize, ch: char| -> bool {
        if !asks || !TextLayoutPolicy::is_regional_stop(ch) {
            return false;
        }
        if let Some(&(_, _, known)) = measured.iter().find(|m| m.0 == span && m.1 == ch) {
            return known;
        }
        let known = centred(&paragraph.children()[span], ch);
        measured.push((span, ch, known));
        known
    };
    let mut previous: Option<(usize, usize, char)> = None;
    for (span, text) in texts.iter().enumerate() {
        for (index, ch) in text.chars().enumerate() {
            if line_breaks::is_inserted_zero_width(ch) {
                continue;
            }
            if let Some((before_span, before_index, before)) = previous {
                let a = &paragraph.children()[before_span];
                let b = &paragraph.children()[span];
                if !a.has_ruby() && !b.has_ruby() && !a.is_warichu() && !b.is_warichu() {
                    let em = a.font_size.min(b.font_size);
                    let drawn = (is_centred(before_span, before), is_centred(span, ch));
                    let spacing = policy.drawn_pair_spacing_em(before, ch, false, drawn);
                    // `palt` already sets punctuation proportionally.
                    let compression = if a.font_features == FontFeatures::Palt
                        || b.font_features == FontFeatures::Palt
                    {
                        0.0
                    } else {
                        spacing.compression
                    };
                    let amount = (compression - spacing.script_gap) * em;
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

/// Add a span to a horizontal paragraph builder, a warichu span as one
/// piece (see `add_horizontal_span_pieces`).
#[cfg(test)]
pub(crate) fn add_horizontal_span(
    builder: &mut impl SpacedParagraphBuilder,
    span: &TextSpan,
    builder_text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) {
    add_horizontal_span_pieces(
        builder,
        span,
        builder_text,
        &[],
        sheds,
        extra,
        text_style,
        fonts,
    );
}

/// Add a span to a horizontal paragraph builder. Each warichu piece of the
/// span (`pieces`, all of it when empty) becomes one inline placeholder so
/// its two lines wrap as a unit; its glyphs are painted after layout. The
/// characters at `sheds` (from `horizontal_pair_spacing`) set narrower by
/// their amounts; `extra` (from `horizontal_ruby_spacing`) adds
/// letter-spacing to characters.
#[allow(clippy::too_many_arguments)]
pub(crate) fn add_horizontal_span_pieces(
    builder: &mut impl SpacedParagraphBuilder,
    span: &TextSpan,
    builder_text: &str,
    pieces: &[WarichuPiece],
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    text_style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) {
    if span.is_warichu() {
        let builder = builder.paragraph_builder();
        if builder_text.starts_with(line_breaks::FORCED_LINE_BREAK) {
            builder.add_text(line_breaks::FORCED_LINE_BREAK.to_string());
        }
        let text = span.apply_text_transform();
        let whole = [WarichuPiece {
            chars: 0..text.chars().count(),
            utf16: 0..text.encode_utf16().count(),
        }];
        let pieces = if pieces.is_empty() {
            &whole[..]
        } else {
            pieces
        };
        let mut mini_style = text_style.clone();
        mini_style.set_font_size(span.font_size * WARICHU_FONT_SCALE);
        mini_style.set_height(1.0);
        mini_style.set_height_override(true);
        mini_style.set_letter_spacing(span.letter_spacing * WARICHU_FONT_SCALE);
        let measure = |line: &str| mini_paragraph(line, &mini_style, fonts).longest_line();
        // SkParagraph does not expose a placeholder's TextStyle through line
        // metrics. A near-zero, inkless NBSP carries the span style for the
        // paint pass; the zero-width space after it allows a line break after
        // the atomic placeholder.
        let mut anchor_style = text_style.clone();
        anchor_style.set_font_size(0.01);
        anchor_style.set_height(0.01);
        anchor_style.set_height_override(true);
        anchor_style.set_letter_spacing(0.0);
        let height = span.font_size.max(0.01);
        for piece in pieces {
            let piece_text: String = text
                .chars()
                .skip(piece.chars.start)
                .take(piece.chars.len())
                .collect();
            let (first, second) = warichu_text_lines(&piece_text);
            let width = measure(first).max(measure(second)).max(0.01);
            builder.add_placeholder(&PlaceholderStyle::new(
                width,
                height,
                PlaceholderAlignment::Middle,
                TextBaseline::Alphabetic,
                height,
            ));
            builder.push_style(&anchor_style);
            builder.add_text(HORIZONTAL_WARICHU_STYLE_ANCHOR.to_string());
            builder.add_text(HORIZONTAL_WARICHU_BREAK_ANCHOR.to_string());
            builder.pop();
        }
        builder.push_style(&anchor_style);
    } else {
        let heights = fallback_run_heights(builder_text, text_style, fonts);
        add_text_with_sheds(
            builder,
            span,
            builder_text,
            sheds,
            extra,
            heights.as_ref(),
            text_style,
        );
    }
}

/// Line height multipliers of the characters of a span `text` set in
/// `style`, with the box of a line of the span in its own font, or None when
/// every character draws from the span's own font.
/// As in CSS, a line takes its height from the span's own font: glyphs drawn
/// from a fallback font, spaces and the zero-width characters the line
/// breaker inserts must not make it taller. With half-leading a run sits
/// centred on its own font's ascent and descent, so a fallback font whose
/// ascent/descent ratio differs pushes the line past the line height. Such a
/// run gets a height that keeps it inside the box of the span's font, and
/// blank characters a height that fits any box. `LayoutParagraph` gives the
/// lines of the span the box, as the span's own font would.
fn fallback_run_heights(
    text: &str,
    style: &skia::textlayout::TextStyle,
    fonts: &skia::textlayout::FontCollection,
) -> Option<SpanRunHeights> {
    let size = style.font_size();
    let height = style.height();
    if !style.height_override() || size <= 0.0 || height <= 0.0 {
        return None;
    }
    let families: Vec<String> = style.font_families().iter().map(str::to_string).collect();
    let typefaces = fonts.clone().find_typefaces(&families, style.font_style());
    let (primary, others) = typefaces.split_first()?;
    let has_glyph = |typeface: &skia::Typeface, ch: char| typeface.unichar_to_glyph(ch as i32) != 0;
    let blank = |ch: char| {
        ch.is_whitespace()
            || line_breaks::is_inserted_zero_width(ch)
            || matches!(ch, '\u{200B}'..='\u{200F}' | '\u{2060}'..='\u{2064}' | '\u{FEFF}')
    };
    if !text
        .chars()
        .any(|ch| !blank(ch) && !has_glyph(primary, ch) && others.iter().any(|t| has_glyph(t, ch)))
    {
        return None;
    }
    // Ascent minus descent: half-leading puts the baseline half of it below
    // the centre of the line.
    let balance = |typeface: &skia::Typeface| {
        let (_, metrics) = Font::from_typeface(typeface, size).metrics();
        -metrics.ascent - metrics.descent
    };
    let own = balance(primary);
    let mut balances: Vec<Option<f32>> = vec![None; others.len()];
    let blank_height = 0.01;
    let line = height * size;
    let heights = text
        .chars()
        .map(|ch| {
            if blank(ch) {
                return blank_height;
            }
            if has_glyph(primary, ch) {
                return height;
            }
            let Some(index) = others.iter().position(|t| has_glyph(t, ch)) else {
                return height;
            };
            let other = *balances[index].get_or_insert_with(|| balance(&others[index]));
            (height - (own - other).abs() / size).max(blank_height)
        })
        .collect();
    Some(SpanRunHeights {
        heights,
        ascent: (line + own) / 2.0,
        descent: (line - own) / 2.0,
    })
}

/// `fallback_run_heights` of a span.
struct SpanRunHeights {
    /// Line height multiplier of each character.
    heights: Vec<f32>,
    /// Box of a line of the span in its own font.
    ascent: f32,
    descent: f32,
}

/// Builder UTF-16 window that covers a whole paragraph.
pub(crate) const FULL_WINDOW: std::ops::Range<usize> = 0..usize::MAX;

/// Part of a span's builder `text` inside the builder UTF-16 `window`, with
/// the char index of its first character, or None when the span lies
/// outside the window. A warichu box is atomic: kept whole when it starts
/// inside the window.
pub(crate) fn window_slice<'a>(
    text: &'a str,
    range: &HorizontalSpanRange,
    window: &std::ops::Range<usize>,
) -> Option<(&'a str, usize)> {
    if range.builder_end <= window.start || range.builder_start >= window.end {
        return None;
    }
    if range.warichu {
        // The window keeps the pieces whose box starts inside it, and the
        // text with any leading break only with the first piece.
        let inside = window_pieces(range, window);
        if inside.is_empty() {
            return None;
        }
        let first = range.builder_start >= window.start;
        return Some((if first { text } else { "" }, 0));
    }
    let mut utf16 = range.builder_start;
    let mut from = None;
    let mut to = text.len();
    for (index, (byte, ch)) in text.char_indices().enumerate() {
        if from.is_none() && utf16 >= window.start {
            from = Some((byte, index));
        }
        if utf16 >= window.end {
            to = byte;
            break;
        }
        utf16 += ch.len_utf16();
    }
    let (from_byte, from_char) = from?;
    Some((&text[from_byte..to.max(from_byte)], from_char))
}

/// Warichu pieces of `range` whose box starts inside the builder UTF-16
/// `window`.
pub(crate) fn window_pieces(
    range: &HorizontalSpanRange,
    window: &std::ops::Range<usize>,
) -> Vec<WarichuPiece> {
    range
        .pieces
        .iter()
        .enumerate()
        .filter(|(index, _)| window.contains(&range.piece_builder_start(*index)))
        .map(|(_, piece)| piece.clone())
        .collect()
}

/// Per-char `entries` of a span shifted to a slice starting at char `from`
/// and `len` chars long.
pub(crate) fn window_entries(
    entries: &[(usize, f32)],
    from: usize,
    len: usize,
) -> Vec<(usize, f32)> {
    entries
        .iter()
        .filter(|(index, _)| *index >= from && *index < from + len)
        .map(|(index, amount)| (index - from, *amount))
        .collect()
}

/// Add `text`, adjusting the letter-spacing of single characters so the
/// builder text stays unchanged: those at `sheds` lose their amount and
/// those in `extra` take its added spacing. Skia tracks every cluster,
/// including the zero-width word joiners the kinsoku pass inserts, so those
/// take no letter-spacing. The builder records how far each adjusted
/// character departs from the span tracking. `heights` set the line height
/// of the runs and the box of the lines of the span.
fn add_text_with_sheds(
    builder: &mut impl SpacedParagraphBuilder,
    span: &TextSpan,
    text: &str,
    sheds: &[(usize, f32)],
    extra: &[(usize, f32)],
    heights: Option<&SpanRunHeights>,
    text_style: &skia::textlayout::TextStyle,
) {
    let tracking = text_style.letter_spacing();
    let base_height = text_style.height();
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
    let letter_spacings: Vec<f32> = text
        .chars()
        .zip(&amounts)
        .map(|(ch, (added, shed))| {
            // Inserted joiners take no tracking, only the spacing set on
            // them (the leading ruby spacer).
            if line_breaks::is_inserted_zero_width(ch) {
                added - shed
            } else {
                tracking + added - shed
            }
        })
        .collect();
    let height_at = |index: usize| {
        heights
            .and_then(|h| h.heights.get(index).copied())
            .unwrap_or(base_height)
    };
    let restyled =
        (0..count).any(|i| letter_spacings[i] != tracking || height_at(i) != base_height);
    if !restyled {
        add_text_with_tabs(builder.paragraph_builder(), text, span.font_size);
        return;
    }
    // UTF-16 offset of the span text in the builder. `get_text` finalizes the
    // builder, which drops the style block the caller pushed for this span
    // while it holds no text yet: the span text would join the block before
    // it, such as a warichu anchor's near-zero size. Open it again; popped at
    // the end.
    let start = {
        let paragraph_builder = builder.paragraph_builder();
        let start = paragraph_builder.get_text().encode_utf16().count();
        paragraph_builder.push_style(text_style);
        start
    };
    if let Some(heights) = heights {
        let end = start + text.encode_utf16().count();
        builder.add_line_box(start..end, heights.ascent, heights.descent);
    }
    // Pieces of characters that share a style: (letter-spacing, height), or
    // None for the span style. A character with its own letter-spacing is a
    // piece of its own.
    type PieceStyle = Option<(f32, f32)>;
    let mut pieces: Vec<(std::ops::Range<usize>, PieceStyle)> = Vec::new();
    let mut utf16 = 0;
    for (index, (byte, ch)) in text.char_indices().enumerate() {
        let at = utf16;
        utf16 += ch.len_utf16();
        let letter_spacing = letter_spacings[index];
        let height = height_at(index);
        let own_spacing = letter_spacing != tracking;
        if own_spacing {
            builder.add_extra_spacing(start + at, letter_spacing - tracking);
        }
        let key = (own_spacing || height != base_height).then_some((letter_spacing, height));
        let end = byte + ch.len_utf8();
        match pieces.last_mut() {
            Some((range, last)) if *last == key && !own_spacing && range.end == byte => {
                range.end = end;
            }
            _ => pieces.push((byte..end, key)),
        }
        if own_spacing {
            // Keep the next character out of this piece.
            pieces.push((end..end, None));
        }
    }
    let paragraph_builder = builder.paragraph_builder();
    for (range, key) in pieces {
        if range.is_empty() {
            continue;
        }
        match key {
            None => add_text_with_tabs(paragraph_builder, &text[range], span.font_size),
            Some((letter_spacing, height)) => {
                let mut style = text_style.clone();
                style.set_letter_spacing(letter_spacing);
                style.set_height(height);
                paragraph_builder.push_style(&style);
                add_text_with_tabs(paragraph_builder, &text[range], span.font_size);
                paragraph_builder.pop();
            }
        }
    }
    builder.paragraph_builder().pop();
}

/// The horizontal passes of one paragraph that layout, painting, position
/// data and the editor share: layout texts, offsets, pair spacing
/// (punctuation aki and script gaps) and long-ruby spacing. `TextContent::horizontal_plans` caches them.
pub(crate) struct HorizontalParagraphPlan {
    pub(crate) texts: Vec<String>,
    pub(crate) offsets: HorizontalOffsets,
    pub(crate) pair_spacing: Vec<Vec<(usize, f32)>>,
    pub(crate) ruby_spacing: HorizontalRubySpacing,
    emergency_breaks: Vec<usize>,
    /// Char offsets where each span's warichu note continues on a later
    /// line, in its transformed text; empty outside warichu.
    warichu_splits: Vec<Vec<usize>>,
    overflow_width: Option<f32>,
}

impl HorizontalParagraphPlan {
    pub(crate) fn new(paragraph: &Paragraph) -> Self {
        Self::with_emergency_breaks(paragraph, &[])
    }

    pub(super) fn with_emergency_breaks(paragraph: &Paragraph, emergency: &[usize]) -> Self {
        Self::with_breaks(paragraph, emergency, &[])
    }

    pub(super) fn with_breaks(
        paragraph: &Paragraph,
        emergency: &[usize],
        forced: &[usize],
    ) -> Self {
        Self::with_warichu_splits(paragraph, emergency, forced, &[])
    }

    pub(super) fn with_warichu_splits(
        paragraph: &Paragraph,
        emergency: &[usize],
        forced: &[usize],
        warichu_splits: &[Vec<usize>],
    ) -> Self {
        let (texts, offset_map) = layout_span_texts_with_breaks(paragraph, emergency, forced);
        let pair_spacing = horizontal_pair_spacing(paragraph, &texts);
        let ruby_spacing = horizontal_ruby_spacing(paragraph, &texts, &offset_map);
        let offsets =
            HorizontalOffsets::from_layout_texts(paragraph, &texts, offset_map, warichu_splits);
        Self {
            texts,
            offsets,
            pair_spacing,
            ruby_spacing,
            emergency_breaks: emergency.to_vec(),
            warichu_splits: warichu_splits.to_vec(),
            overflow_width: None,
        }
    }

    /// Warichu pieces of span `span`, one box each; empty outside warichu.
    pub(crate) fn warichu_pieces(&self, span: usize) -> &[WarichuPiece] {
        self.offsets
            .ranges
            .get(span)
            .map_or(&[], |range| range.pieces.as_slice())
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

/// Top of the em box of a horizontal base laid out in `rect`, whose builder
/// text starts at UTF-16 `base_start`. Annotations attach to this box: the
/// ideographic em box (OS/2 typo metrics) of the font that shaped the base,
/// centred on the line baseline. Skia's tight rect spans the hhea ascent and
/// descent instead, which in CJK faces reach past the em box.
pub(crate) fn horizontal_base_em_top(
    laid_out: &LayoutParagraph,
    lines: &[skia::textlayout::LineMetrics],
    base_start: usize,
    font_size: f32,
    rect: skia::Rect,
) -> f32 {
    let middle = rect.center_y();
    let Some(line) = lines.iter().find(|line| {
        let baseline = line.baseline as f32;
        middle >= baseline - line.ascent as f32 && middle <= baseline + line.descent as f32
    }) else {
        return rect.top.max(rect.bottom - font_size);
    };
    // Marks of one line share a baseline: in a line holding CJK text, the
    // em box is that of its first CJK character, whatever font draws a
    // Latin base.
    let cjk_start = {
        let mut utf16 = 0usize;
        laid_out.text().chars().find_map(|ch| {
            let at = utf16;
            utf16 += ch.len_utf16();
            (at >= line.start_index && at < line.end_index && is_cjk_text_char(ch)).then_some(at)
        })
    };
    let font = laid_out.font_at_utf16(cjk_start.unwrap_or(base_start.max(line.start_index)));
    let (ascent, descent) = super::text_shaping::upright_centre_metrics(&font);
    line.baseline as f32 + (ascent + descent - font_size) / 2.0
}
