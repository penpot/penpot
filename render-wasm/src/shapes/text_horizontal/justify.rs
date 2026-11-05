// Line adjustment of horizontal CJK text. SkParagraph only expands around
// ideographs, which bunches the space next to kanji. Japanese justification
// (JLREQ §3.8) spreads each line's slack evenly between its characters, and
// Japanese squeezing (oikomi) reduces punctuation aki to keep the next
// character. The other policies justify over their pairwise expansion
// opportunities with bounded amounts.

use crate::shapes::text_allocation::{
    allocate_bounded_expansion, bounded_expansion, capped_equal_allocations,
};
use crate::shapes::text_paragraph::LayoutParagraph;

use super::*;

/// Slack kept back from each justified line so float noise never wraps it.
const JUSTIFY_EPSILON: f32 = 0.05;

/// True for a character that belongs to a Western word: no space opens
/// between two of them.
fn is_word_char(ch: char) -> bool {
    !ch.is_whitespace() && !is_cjk_text_char(ch) && !line_breaks::is_inserted_zero_width(ch)
}

/// Per-span letter-spacing (char index in the layout text, amount) that
/// justifies `laid_out`, the paragraph of `plan` laid out unjustified at
/// `width`. Every line but the last spreads its slack evenly over the gaps
/// between its characters, skipping gaps inside a Western word and the
/// zero-width word joiners; warichu notes take none.
pub(crate) fn horizontal_jlreq_justify_spacing(
    laid_out: &LayoutParagraph,
    plan: &HorizontalParagraphPlan,
    width: f32,
) -> Vec<Vec<(usize, f32)>> {
    let mut spacing: Vec<Vec<(usize, f32)>> = vec![Vec::new(); plan.texts.len()];
    // (builder UTF-16 offset, span, char index, char) of every character.
    let mut chars: Vec<(usize, usize, usize, char)> = Vec::new();
    for range in &plan.offsets.ranges {
        if range.warichu {
            continue;
        }
        let Some(text) = plan.texts.get(range.span) else {
            continue;
        };
        let mut utf16 = range.builder_start;
        for (index, ch) in text.chars().enumerate() {
            if !line_breaks::is_inserted_zero_width(ch) {
                chars.push((utf16, range.span, index, ch));
            }
            utf16 += ch.len_utf16();
        }
    }
    let lines = laid_out.get_line_metrics();
    let Some((_, justified)) = lines.split_last() else {
        return spacing;
    };
    for line in justified {
        let slack = width - line.width as f32 - JUSTIFY_EPSILON;
        if slack <= 0.0 {
            continue;
        }
        let in_line: Vec<&(usize, usize, usize, char)> = chars
            .iter()
            .filter(|(utf16, ..)| {
                *utf16 >= line.start_index && *utf16 < line.end_excluding_whitespaces
            })
            .collect();
        let gaps: Vec<&(usize, usize, usize, char)> = in_line
            .windows(2)
            .filter(|pair| !(is_word_char(pair[0].3) && is_word_char(pair[1].3)))
            .map(|pair| pair[0])
            .collect();
        if gaps.is_empty() {
            continue;
        }
        let share = slack / gaps.len() as f32;
        for (_, span, index, _) in gaps {
            spacing[*span].push((*index, share));
        }
    }
    spacing
}

/// Pairwise, bounded justification; explicit distribution fills every
/// line without a per-gap cap. Annotation and grapheme interiors stay solid.
pub(crate) fn horizontal_pairwise_justify_spacing(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
    plan: &HorizontalParagraphPlan,
    width: f32,
) -> Vec<Vec<(usize, f32)>> {
    use unicode_segmentation::UnicodeSegmentation;
    let distribute = paragraph.text_align() == crate::shapes::TextAlign::Distribute;
    let policy = paragraph.layout_policy();
    let chars = line_chars(paragraph, plan);
    let source: String = chars.iter().map(|c| c.ch).collect();
    let original: Vec<_> = paragraph
        .children()
        .iter()
        .flat_map(|span| span.text.chars())
        .collect();
    let starts: Vec<_> = source
        .graphemes(true)
        .scan(0usize, |at, g| {
            let start = *at;
            *at += g.chars().count();
            Some(start)
        })
        .collect();
    let mut result = vec![Vec::new(); plan.texts.len()];
    let notes: Vec<_> = plan
        .offsets
        .ranges
        .iter()
        .filter(|range| range.warichu)
        .collect();
    let lines = laid_out.get_line_metrics();
    let count = if distribute {
        lines.len()
    } else {
        lines.len().saturating_sub(1)
    };
    for line in lines.iter().take(count) {
        let remaining = (width - line.width as f32 - JUSTIFY_EPSILON).max(0.0);
        let mut opportunities = Vec::new();
        let start = chars.partition_point(|c| c.utf16 < line.start_index);
        let end = chars.partition_point(|c| c.utf16 < line.end_excluding_whitespaces);
        for index in start..end.saturating_sub(1) {
            let (before, after) = (&chars[index], &chars[index + 1]);
            let source_before = plan.offsets.builder_to_source(before.utf16);
            let source_after = plan.offsets.builder_to_source(after.utf16);
            let nonbreaking = distribute
                && original
                    .get(source_before..=source_after)
                    .is_some_and(|pair| {
                        pair.iter()
                            .any(|ch| matches!(ch, '\u{00a0}' | '\u{202f}' | '\u{2060}'))
                    });
            let note = notes.get(notes.partition_point(|r| r.builder_end <= before.utf16));
            if before.utf16 < line.start_index
                || after.utf16 >= line.end_excluding_whitespaces
                || (!distribute && after.joined)
                || nonbreaking
                || starts.binary_search(&(index + 1)).is_err()
                || (distribute
                    && before.span == after.span
                    && paragraph.children()[before.span].has_ruby())
                || note.is_some_and(|r| {
                    r.builder_start > before.utf16 && r.builder_start < after.utf16
                })
            {
                continue;
            }
            let em = before.font_size.min(after.font_size);
            if let Some((priority, cap)) =
                bounded_expansion(policy, before.ch, after.ch, em, distribute.then_some(width))
            {
                opportunities.push((index, priority, cap));
            }
        }
        for (index, amount) in allocate_bounded_expansion(&opportunities, remaining) {
            let ch = &chars[index];
            result[ch.span].push((ch.index, amount));
        }
    }
    result
}

/// Lays a paragraph out with extra per-span sheds (char index, amount).
/// Lays out a paragraph with extra sheds, restricted to a builder UTF-16
/// window (`FULL_WINDOW` for all of it).
pub(crate) type LayOutWithSheds<'a> =
    dyn Fn(&[Vec<(usize, f32)>], std::ops::Range<usize>) -> LayoutParagraph + 'a;

/// Builder UTF-16 units first laid out from a line start: a few lines of
/// text. The window doubles until it holds the lines a decision needs.
const SQUEEZE_WINDOW: usize = 512;

/// One real character of a horizontal paragraph in builder order.
struct LineChar {
    /// UTF-16 offset in the builder text.
    utf16: usize,
    span: usize,
    /// Char index in the span's layout text.
    index: usize,
    ch: char,
    class: Option<JlreqClass>,
    font_size: f32,
    /// A WORD JOINER before it forbids a break.
    joined: bool,
}

fn line_chars(paragraph: &Paragraph, plan: &HorizontalParagraphPlan) -> Vec<LineChar> {
    let spans = paragraph.children();
    let mut chars = Vec::new();
    for range in &plan.offsets.ranges {
        let (Some(span), Some(text)) = (spans.get(range.span), plan.texts.get(range.span)) else {
            continue;
        };
        if range.warichu {
            continue;
        }
        let proportional = span.font_features == FontFeatures::Palt;
        let mut utf16 = range.builder_start;
        let mut joined = false;
        for (index, ch) in text.chars().enumerate() {
            if ch == line_breaks::WORD_JOINER {
                joined = true;
            } else if !line_breaks::is_inserted_zero_width(ch) {
                chars.push(LineChar {
                    utf16,
                    span: range.span,
                    index,
                    ch,
                    class: aki_class(ch, span.has_ruby(), proportional),
                    font_size: span.font_size,
                    joined,
                });
                joined = false;
            }
            utf16 += ch.len_utf16();
        }
    }
    chars
}

/// Chars `[start, end)` that start a line at `start` and move as one: a
/// word joiner or a Western word keeps the next character with them.
fn line_start_unit(chars: &[LineChar], start: usize) -> std::ops::Range<usize> {
    let mut end = start + 1;
    while end < chars.len()
        && (chars[end].joined || (is_word_char(chars[end - 1].ch) && is_word_char(chars[end].ch)))
    {
        end += 1;
    }
    start..end
}

/// Squeezable spacing (char, priority, px) after each char of `range`: what
/// the JLREQ pair table lets oikomi take between it and the next char.
fn squeeze_capacities(chars: &[LineChar], range: std::ops::Range<usize>) -> Vec<(usize, u8, f32)> {
    range
        .filter(|at| at + 1 < chars.len())
        .filter_map(|at| {
            let (before, after) = (&chars[at], &chars[at + 1]);
            let rule = pair_rule(before.class?, after.class?);
            let em = before.font_size.min(after.font_size);
            let capacity = (rule.preferred_em - rule.minimum_em) * em;
            (rule.shrink_priority > 0 && capacity > 0.0).then_some((
                at,
                rule.shrink_priority,
                capacity,
            ))
        })
        .collect()
}

/// Per-span sheds (char index, amount) that squeeze lines of a horizontal
/// Japanese paragraph to keep the character that would start the next line
/// (JLREQ §3.8 oikomi). Each line, in order, takes the unit starting the next
/// line when the pair table's squeezable aki on the line covers the
/// overflow, reduced in priority order and equally within a priority. A
/// trailing closing bracket or comma pulled to the line end sheds its aki
/// there. `lay_out` lays the paragraph out with extra sheds; returns the
/// sheds and the final layout.
pub(crate) fn horizontal_squeeze_sheds(
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    width: f32,
    lay_out: &LayOutWithSheds,
) -> (Vec<Vec<(usize, f32)>>, LayoutParagraph) {
    let chars = line_chars(paragraph, plan);
    let mut sheds: Vec<Vec<(usize, f32)>> = vec![Vec::new(); plan.texts.len()];
    let total = plan
        .offsets
        .ranges
        .iter()
        .map(|range| range.builder_end)
        .max()
        .unwrap_or(0);
    // Lines are broken greedily, so a line and the decision about it depend
    // only on the text from its start. Lay out that text alone, enough of it
    // for the line, the next one and what decides the next break: relaying
    // out the whole paragraph per line made long paragraphs quadratic.
    let lay_out_from = |sheds: &[Vec<(usize, f32)>], start: usize| {
        let mut size = SQUEEZE_WINDOW;
        loop {
            let end = start.saturating_add(size).min(total);
            let laid_out = lay_out(sheds, start..end);
            if end >= total || laid_out.get_line_metrics().len() >= 3 {
                return laid_out;
            }
            size *= 2;
        }
    };
    // `window` is laid out from `window_start` with the current sheds; its
    // lines stay valid until a squeeze changes the sheds, so lines without a
    // candidate move on inside it instead of laying out again.
    let mut window_start = 0usize;
    let mut window = lay_out_from(&sheds, window_start);
    let mut line_index = 0usize;
    for _ in 0..chars.len() + 1 {
        let window_lines = window.get_line_metrics();
        // A line is decided by the next one and what follows it: past that,
        // lay out again from the line.
        let reached_end = window_lines
            .last()
            .is_some_and(|last| window_start + last.end_index >= total);
        if line_index > 0 && line_index + 2 >= window_lines.len() && !reached_end {
            window_start += window_lines.get(line_index).map_or_else(
                || window_lines.last().map_or(0, |last| last.end_index),
                |line| line.start_index,
            );
            window = lay_out_from(&sheds, window_start);
            line_index = 0;
            continue;
        }
        let start = window_start
            + window_lines
                .get(line_index)
                .map_or(0, |line| line.start_index);
        let laid_out = &window;
        let lines: Vec<_> = window_lines.iter().skip(line_index).collect();
        if lines.len() < 2 {
            break;
        }
        let (line, next) = (lines[0], lines[1]);
        let next_start = window_start + next.start_index;
        let Some(unit_start) = chars.iter().position(|c| c.utf16 >= next_start) else {
            break;
        };
        let unit = line_start_unit(&chars, unit_start);
        let unit_end_utf16 = chars
            .get(unit.end)
            .map_or(window_start + next.end_index, |c| c.utf16);
        let unit_width = laid_out
            .get_rects_for_range(
                chars[unit.start].utf16 - window_start..unit_end_utf16 - window_start,
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            )
            .iter()
            .map(|rect| rect.rect.width())
            .sum::<f32>();
        let last = &chars[unit.end - 1];
        let trailing_aki = match last.class {
            Some(JlreqClass::ClosingBracket | JlreqClass::Comma) => last.font_size / 2.0,
            _ => 0.0,
        };
        let needed = line.width as f32 + unit_width - trailing_aki - width + JUSTIFY_EPSILON;
        let line_chars_end = unit.start;
        let line_chars_start = chars
            .iter()
            .position(|c| c.utf16 >= start)
            .unwrap_or(line_chars_end);
        let capacities = squeeze_capacities(&chars, line_chars_start..line_chars_end);
        let capacity: f32 = capacities.iter().map(|(_, _, cap)| cap).sum();
        if needed <= 0.0 || capacity < needed {
            line_index += 1;
            continue;
        }
        let mut candidate = sheds.clone();
        let mut remaining = needed;
        for priority in 1..=5 {
            let caps: Vec<(usize, f32)> = capacities
                .iter()
                .filter(|(_, p, _)| *p == priority)
                .map(|(at, _, cap)| (*at, *cap))
                .collect();
            for (at, amount) in capped_equal_allocations(&caps, remaining) {
                candidate[chars[at].span].push((chars[at].index, amount));
                remaining -= amount;
            }
            if remaining <= 0.0 {
                break;
            }
        }
        if trailing_aki > 0.0 {
            candidate[last.span].push((last.index, trailing_aki));
        }
        // The check needs this line only: lay out to the end of the pulled
        // unit plus a line's worth of what follows.
        let check_end = unit_end_utf16
            .saturating_add(unit_end_utf16 - start)
            .min(total);
        let squeezed = lay_out(&candidate, start..check_end);
        let squeezed_lines = squeezed.get_line_metrics();
        let kept = squeezed_lines
            .first()
            .is_some_and(|squeezed_line| start + squeezed_line.end_index >= unit_end_utf16);
        if kept {
            sheds = candidate;
            if check_end >= total && squeezed_lines.len() < 2 {
                break;
            }
            window_start = start;
            window = squeezed;
            line_index = 1;
        } else {
            line_index += 1;
        }
    }
    let laid_out = lay_out(&sheds, FULL_WINDOW);
    (sheds, laid_out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::{TextAlign, TextDirection};
    use skia::textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle, TextStyle};
    use std::cell::Cell;

    type LayOutFull<'a> = dyn Fn(&[Vec<(usize, f32)>]) -> LayoutParagraph + 'a;

    /// The squeeze pass as it was before windowing: one layout of the whole
    /// paragraph per line.
    fn full_relayout_sheds(
        paragraph: &Paragraph,
        plan: &HorizontalParagraphPlan,
        width: f32,
        lay_out: &LayOutFull,
    ) -> Vec<Vec<(usize, f32)>> {
        let chars = line_chars(paragraph, plan);
        let mut sheds: Vec<Vec<(usize, f32)>> = vec![Vec::new(); plan.texts.len()];
        let mut laid_out = lay_out(&sheds);
        let mut line_index = 0usize;
        for _ in 0..chars.len() + 1 {
            let lines = laid_out.get_line_metrics();
            if line_index + 1 >= lines.len() {
                break;
            }
            let (line, next) = (&lines[line_index], &lines[line_index + 1]);
            let Some(unit_start) = chars.iter().position(|c| c.utf16 >= next.start_index) else {
                break;
            };
            let unit = line_start_unit(&chars, unit_start);
            let unit_end_utf16 = chars.get(unit.end).map_or(next.end_index, |c| c.utf16);
            let unit_width = laid_out
                .get_rects_for_range(
                    chars[unit.start].utf16..unit_end_utf16,
                    RectHeightStyle::Tight,
                    RectWidthStyle::Tight,
                )
                .iter()
                .map(|rect| rect.rect.width())
                .sum::<f32>();
            let last = &chars[unit.end - 1];
            let trailing_aki = match last.class {
                Some(JlreqClass::ClosingBracket | JlreqClass::Comma) => last.font_size / 2.0,
                _ => 0.0,
            };
            let needed = line.width as f32 + unit_width - trailing_aki - width + JUSTIFY_EPSILON;
            let line_chars_end = unit.start;
            let line_chars_start = chars
                .iter()
                .position(|c| c.utf16 >= line.start_index)
                .unwrap_or(line_chars_end);
            let capacities = squeeze_capacities(&chars, line_chars_start..line_chars_end);
            let total: f32 = capacities.iter().map(|(_, _, cap)| cap).sum();
            if needed <= 0.0 || total < needed {
                line_index += 1;
                continue;
            }
            let mut candidate = sheds.clone();
            let mut remaining = needed;
            for priority in 1..=5 {
                let caps: Vec<(usize, f32)> = capacities
                    .iter()
                    .filter(|(_, p, _)| *p == priority)
                    .map(|(at, _, cap)| (*at, *cap))
                    .collect();
                for (at, amount) in capped_equal_allocations(&caps, remaining) {
                    candidate[chars[at].span].push((chars[at].index, amount));
                    remaining -= amount;
                }
                if remaining <= 0.0 {
                    break;
                }
            }
            if trailing_aki > 0.0 {
                candidate[last.span].push((last.index, trailing_aki));
            }
            let squeezed = lay_out(&candidate);
            if squeezed
                .get_line_metrics()
                .get(line_index)
                .is_some_and(|squeezed_line| squeezed_line.end_index >= unit_end_utf16)
            {
                sheds = candidate;
                laid_out = squeezed;
            }
            line_index += 1;
        }
        sheds
    }

    #[test]
    fn windowed_squeeze_matches_full_relayout_and_lays_out_the_paragraph_once() {
        crate::globals::design_init();
        let text = "あい、うえ、おかきくけこさしすせそ「かぎ」、と。".repeat(40);
        let span = TextSpan {
            text,
            font_size: 16.0,
            line_height: 1.0,
            ..TextSpan::default()
        };
        let mut paragraph = Paragraph::new(
            TextAlign::default(),
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        );
        paragraph.set_language("ja".to_string());
        let plan = HorizontalParagraphPlan::new(&paragraph);
        let mut style = TextStyle::default();
        style.set_font_size(16.0);
        let mut fonts = FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut paragraph_style = ParagraphStyle::default();
        paragraph_style.set_apply_rounding_hack(false);
        let width = 16.0 * 7.6;
        let full_layouts = Cell::new(0usize);
        let lay_out = |extra: &[Vec<(usize, f32)>], window: std::ops::Range<usize>| {
            if window == FULL_WINDOW {
                full_layouts.set(full_layouts.get() + 1);
            }
            let sheds: Vec<(usize, f32)> = plan.pair_spacing[0]
                .iter()
                .chain(extra.first().into_iter().flatten())
                .copied()
                .collect();
            let (text, from) =
                window_slice(&plan.texts[0], &plan.offsets.ranges[0], &window).unwrap_or(("", 0));
            let sheds = window_entries(&sheds, from, text.chars().count());
            let mut builder = ParagraphBuilder::new(&paragraph_style, &fonts);
            builder.push_style(&style);
            add_horizontal_span(
                &mut builder,
                &paragraph.children()[0],
                text,
                &sheds,
                &[],
                &style,
                &fonts,
            );
            let mut laid_out = LayoutParagraph::build(&mut builder);
            laid_out.layout(width);
            laid_out
        };
        let (windowed, _) = horizontal_squeeze_sheds(&paragraph, &plan, width, &lay_out);
        assert_eq!(
            full_layouts.get(),
            1,
            "the whole paragraph is laid out once"
        );
        let reference = full_relayout_sheds(&paragraph, &plan, width, &|extra| {
            lay_out(extra, FULL_WINDOW)
        });
        assert!(!reference[0].is_empty(), "the text squeezes some lines");
        assert_eq!(windowed.len(), reference.len());
        for (got, want) in windowed[0].iter().zip(&reference[0]) {
            assert_eq!(got.0, want.0);
            assert!((got.1 - want.1).abs() < 0.01, "{got:?} vs {want:?}");
        }
        assert_eq!(windowed[0].len(), reference[0].len());
    }
}
