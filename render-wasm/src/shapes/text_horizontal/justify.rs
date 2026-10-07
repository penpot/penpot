// Justification of horizontal Japanese text (JLREQ §3.8). SkParagraph only
// expands around ideographs, which bunches the space next to kanji; this pass
// spreads each line's slack evenly between its characters instead.

use super::*;

/// Slack kept back from each justified line so float noise never wraps it.
const JUSTIFY_EPSILON: f32 = 0.05;

/// True for a character that belongs to a Western word: no space opens
/// between two of them.
fn is_word_char(ch: char) -> bool {
    !ch.is_whitespace() && !is_japanese_text_char(ch) && !kinsoku::is_inserted_zero_width(ch)
}

/// Per-span letter-spacing (char index in the layout text, amount) that
/// justifies `laid_out`, the paragraph of `plan` laid out unjustified at
/// `width`. Every line but the last spreads its slack evenly over the gaps
/// between its characters, skipping gaps inside a Western word and the
/// zero-width word joiners; warichu notes take none.
pub(crate) fn horizontal_justify_spacing(
    laid_out: &skia::textlayout::Paragraph,
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
            if !kinsoku::is_inserted_zero_width(ch) {
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

/// Lays a paragraph out with extra per-span sheds (char index, amount).
pub(crate) type LayOutWithSheds<'a> =
    dyn Fn(&[Vec<(usize, f32)>]) -> skia::textlayout::Paragraph + 'a;

/// One real character of a horizontal paragraph in builder order.
struct LineChar {
    /// UTF-16 offset in the builder text.
    utf16: usize,
    span: usize,
    /// Char index in the span's layout text.
    index: usize,
    ch: char,
    class: Option<JapaneseClass>,
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
            if ch == kinsoku::WORD_JOINER {
                joined = true;
            } else if !kinsoku::is_inserted_zero_width(ch) {
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
) -> (Vec<Vec<(usize, f32)>>, skia::textlayout::Paragraph) {
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
            Some(JapaneseClass::ClosingBracket | JapaneseClass::Comma) => last.font_size / 2.0,
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
        let kept = squeezed
            .get_line_metrics()
            .get(line_index)
            .is_some_and(|squeezed_line| squeezed_line.end_index >= unit_end_utf16);
        if kept {
            sheds = candidate;
            laid_out = squeezed;
        }
        line_index += 1;
    }
    (sheds, laid_out)
}
