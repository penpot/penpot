//! Repair SkParagraph's emergency splits without changing the wrap budget.

use super::*;
use crate::shapes::line_breaks::PolicyBoundaries;
use crate::shapes::text_paragraph::LayoutParagraph;

/// The paragraph's span texts after text transform, joined.
fn transformed_text(paragraph: &Paragraph) -> String {
    paragraph
        .children()
        .iter()
        .map(TextSpan::apply_text_transform)
        .collect()
}

/// UTF-16 ranges, in the transformed text, of the ruby bases
/// (`Paragraph::ruby_groups`) and warichu spans that must stay whole.
fn annotation_ranges(paragraph: &Paragraph) -> Vec<std::ops::Range<usize>> {
    let group_of = paragraph.ruby_group_of_spans();
    let mut offset = 0;
    let mut ranges: Vec<(Option<usize>, std::ops::Range<usize>)> = Vec::new();
    for (index, span) in paragraph.children().iter().enumerate() {
        let start = offset;
        offset += span.apply_text_transform().encode_utf16().count();
        let group = group_of[index];
        match ranges.last_mut() {
            Some((last_group, range)) if group.is_some() && *last_group == group => {
                range.end = offset
            }
            _ if group.is_some() || span.is_warichu() => ranges.push((group, start..offset)),
            _ => {}
        }
    }
    ranges.into_iter().map(|(_, range)| range).collect()
}

/// True when `position` falls strictly inside one of the ascending
/// `annotations` ranges.
fn within_annotation(annotations: &[std::ops::Range<usize>], position: usize) -> bool {
    annotations
        .get(annotations.partition_point(|r| r.end <= position))
        .is_some_and(|r| r.start < position)
}

/// A finite, real wrap width; auto-width and unbounded layout skip repairs.
fn is_wrap_width(width: f32) -> bool {
    width.is_finite() && width > 0.0 && width != f32::MAX
}

impl HorizontalParagraphPlan {
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
        let policy = paragraph.layout_policy();
        if !policy.reopens_overlong_words() || !is_wrap_width(width) {
            return;
        }
        let boundaries = PolicyBoundaries::new(&transformed_text(paragraph), policy);
        let graphemes = &boundaries.graphemes;
        let annotations = annotation_ranges(paragraph);
        let mut units = Vec::new();
        let mut start = 0;
        for (at, grapheme) in graphemes.iter().enumerate().skip(1) {
            if grapheme.allowed {
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
            let first = graphemes[unit.start].utf16;
            let last = graphemes
                .get(unit.end)
                .map_or(boundaries.length, |g| g.utf16);
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
            for grapheme in &graphemes[unit.start + 1..unit.end] {
                if grapheme.emergency && !within_annotation(&annotations, grapheme.utf16) {
                    emergency.push(grapheme.utf16);
                }
            }
        }
        if !emergency.is_empty() {
            *self = Self::with_emergency_breaks(paragraph, &emergency);
        }
    }

    /// Keep an indivisible unit together even when Skia's last-resort wrapping
    /// ignores its joiners. Isolate overflowing units, measure the surrounding
    /// text at the original width, and freeze those legal line starts before
    /// increasing Skia's physical width. All consumers use this same plan.
    pub(crate) fn retain_atomic_overflow(
        &mut self,
        paragraph: &Paragraph,
        width: f32,
        mut measure: impl FnMut(&Self, f32) -> LayoutParagraph,
    ) {
        let policy = paragraph.layout_policy();
        if !policy.keeps_overlong_units() || !is_wrap_width(width) {
            return;
        }
        let boundaries = PolicyBoundaries::new(&transformed_text(paragraph), policy);
        let graphemes = &boundaries.graphemes;
        let length = boundaries.length;
        let annotations = annotation_ranges(paragraph);
        let hard_break = |c| matches!(c, '\n' | '\r' | '\u{2028}' | '\u{2029}');
        let mut units = Vec::new();
        let mut start = 0;
        for at in 1..graphemes.len() {
            let position = graphemes[at].utf16;
            let (before, after) = (graphemes[at - 1].first, graphemes[at].first);
            if hard_break(before)
                || hard_break(after)
                || (!within_annotation(&annotations, position) && graphemes[at].emergency)
            {
                if at - start > 1 {
                    units.push(graphemes[start].utf16..position);
                }
                start = at;
            }
        }
        if graphemes.len() - start > 1 {
            units.push(graphemes[start].utf16..length);
        }
        if units.is_empty() {
            return;
        }
        let bounded = measure(self, width);
        let starts: Vec<_> = bounded
            .get_line_metrics()
            .iter()
            .skip(1)
            .map(|line| self.offsets.builder_to_transformed(line.start_index))
            .collect();
        units.retain(|unit| {
            starts
                .get(starts.partition_point(|at| *at <= unit.start))
                .is_some_and(|at| *at < unit.end)
        });
        if units.is_empty() {
            return;
        }
        let natural = measure(self, f32::MAX);
        let mut physical_width = width;
        units.retain(|unit| {
            let from = self.offsets.transformed_to_builder(unit.start);
            let to = self.offsets.transformed_to_builder(unit.end);
            let extent: f32 = natural
                .get_rects_for_range(from..to, RectHeightStyle::Tight, RectWidthStyle::Tight)
                .iter()
                .map(|box_| box_.rect.width())
                .sum();
            if extent <= width + 0.01 {
                return false;
            }
            physical_width = physical_width.max(extent);
            true
        });
        if units.is_empty() {
            return;
        }
        let can_insert = |at: usize| {
            at > 0
                && at < length
                && graphemes
                    .binary_search_by_key(&at, |g| g.utf16)
                    .ok()
                    .is_some_and(|i| {
                        !hard_break(graphemes[i].first) && !hard_break(graphemes[i - 1].first)
                    })
        };
        let mut forced: Vec<_> = units
            .iter()
            .flat_map(|unit| [unit.start, unit.end])
            .filter(|at| can_insert(*at))
            .collect();
        forced.sort_unstable();
        forced.dedup();
        let isolated = Self::with_breaks(paragraph, &self.emergency_breaks, &forced);
        let bounded = measure(&isolated, width);
        physical_width = physical_width.max(bounded.longest_line());
        forced.extend(
            bounded
                .get_line_metrics()
                .iter()
                .skip(1)
                .map(|line| isolated.offsets.builder_to_transformed(line.start_index))
                .filter(|at| {
                    can_insert(*at)
                        && units
                            .get(units.partition_point(|unit| unit.end <= *at))
                            .is_none_or(|unit| *at <= unit.start)
                }),
        );
        forced.sort_unstable();
        forced.dedup();
        *self = Self::with_breaks(paragraph, &self.emergency_breaks, &forced);
        // Avoid a floating-point edge reintroducing the very fallback being
        // repaired. The epsilon changes physical width, not the wrap budget.
        self.overflow_width = Some(physical_width + 0.01);
    }

    pub(crate) fn layout_width(&self, width: f32) -> f32 {
        self.overflow_width.unwrap_or(width)
    }

    /// Skia aligns against the physical width; translate it back to the box.
    pub(crate) fn x_offset(&self, paragraph: &Paragraph, width: f32) -> f32 {
        use crate::shapes::{TextAlign, TextDirection};
        let slack = width - self.layout_width(width);
        match paragraph.text_align() {
            TextAlign::Center => slack * 0.5,
            TextAlign::Right => slack,
            TextAlign::Start if paragraph.text_direction() == TextDirection::RTL => slack,
            TextAlign::End if paragraph.text_direction() == TextDirection::LTR => slack,
            _ => 0.0,
        }
    }

    pub(crate) fn has_atomic_overflow(&self) -> bool {
        self.overflow_width.is_some()
    }
}
