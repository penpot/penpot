//! Repair SkParagraph's emergency splits without changing the wrap budget.

use super::*;

impl HorizontalParagraphPlan {
    /// Keep an indivisible unit together even when Skia's last-resort wrapping
    /// ignores its joiners. Isolate overflowing units, measure the surrounding
    /// text at the original width, and freeze those legal line starts before
    /// increasing Skia's physical width. All consumers use this same plan.
    pub(crate) fn retain_atomic_overflow(
        &mut self,
        paragraph: &Paragraph,
        width: f32,
        mut measure: impl FnMut(&Self, f32) -> skia::textlayout::Paragraph,
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
        let mut length = 0;
        let graphemes: Vec<_> = source
            .graphemes(true)
            .map(|g| {
                let start = length;
                length += g.encode_utf16().count();
                (start, g.chars().next().unwrap())
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
        let hard_break = |c| matches!(c, '\n' | '\r' | '\u{2028}' | '\u{2029}');
        let mut units = Vec::new();
        let mut start = 0;
        for at in 1..graphemes.len() {
            let (position, after) = graphemes[at];
            let before = graphemes[at - 1].1;
            let annotation = protected
                .get(protected.partition_point(|r| r.end <= position))
                .is_some_and(|r| r.start < position);
            if hard_break(before)
                || hard_break(after)
                || (!annotation && policy.emergency_break_allowed(before, after))
            {
                if at - start > 1 {
                    units.push(graphemes[start].0..position);
                }
                start = at;
            }
        }
        if graphemes.len() - start > 1 {
            units.push(graphemes[start].0..length);
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
                    .binary_search_by_key(&at, |g| g.0)
                    .ok()
                    .is_some_and(|i| !hard_break(graphemes[i].1) && !hard_break(graphemes[i - 1].1))
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
        use skia::textlayout::{TextAlign, TextDirection};
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
