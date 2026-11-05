//! Column planning, spacing and alignment of the pairwise policies (plain,
//! Chinese and Korean) along the vertical flow. Break opportunities, spacing
//! and justification come from `TextLayoutPolicy` pair rules over the whole
//! paragraph text.

use crate::shapes::cjk_chars::is_punctuation;
use crate::shapes::line_breaks::PolicyBoundaries;
use crate::shapes::text_allocation::{allocate_bounded_expansion, bounded_expansion};
use crate::shapes::text_policy::TextLayoutPolicy;
use crate::shapes::TextAlign;
use unicode_segmentation::UnicodeSegmentation;

use super::super::layout::CellKind;
use super::{align_offset_along_column, is_bounded, FlowCell, FIT_TOLERANCE};

/// Column planning of the pairwise policies (plain, Chinese and Korean):
/// break opportunities come from `TextLayoutPolicy::break_allowed`.
/// Boundaries use the entire paragraph, so font and style runs cannot split
/// a grapheme or create a word boundary.
pub(in crate::shapes::text_vertical) fn plan_pairwise_columns(
    cells: &[FlowCell],
    text: &str,
    policy: TextLayoutPolicy,
    max_height: f32,
) -> Vec<(usize, f32)> {
    let boundaries = PolicyBoundaries::new(text, policy);
    let mut emergency_start = Vec::with_capacity(cells.len());
    let mut unit_start = Vec::with_capacity(cells.len());
    let mut prefix = vec![0.0];
    for (i, flow) in cells.iter().enumerate() {
        let boundary = boundaries.at(flow.cell.start);
        let atomic =
            i > 0 && (flow.keep_with_previous || flow.shares_previous_box || boundary.is_none());
        let emergency_joined = atomic || boundary.is_some_and(|(_, emergency)| !emergency);
        emergency_start.push(if i > 0 && emergency_joined {
            emergency_start[i - 1]
        } else {
            i
        });
        let joined = atomic || boundary.is_some_and(|(allowed, _)| !allowed);
        unit_start.push(if i > 0 && joined {
            unit_start[i - 1]
        } else {
            i
        });
        prefix.push(prefix[i] + flow.cell.extent);
    }
    let mut unit_end = vec![cells.len(); cells.len()];
    for i in (0..cells.len().saturating_sub(1)).rev() {
        unit_end[i] = if unit_start[i] == unit_start[i + 1] {
            unit_end[i + 1]
        } else {
            i + 1
        };
    }
    let mut placements = Vec::with_capacity(cells.len());
    let (mut column, mut column_start, mut cursor) = (0, 0, 0.0);
    for (i, flow) in cells.iter().enumerate() {
        let punctuation = |at: usize| cells.get(at).and_then(|c| c.ch).is_some_and(is_punctuation);
        let solitary = (i == 0 || !punctuation(i - 1)) && !punctuation(i + 1);
        let hang = if solitary {
            flow.ch
                .map_or(0.0, |ch| policy.hanging_em(ch, true) * flow.cell.font_size)
        } else {
            0.0
        };
        if cursor > 0.0 && cursor + flow.cell.extent - hang > max_height + FIT_TOLERANCE {
            let candidate = unit_start[i];
            let end = if policy.measures_whole_unit() {
                unit_end[i]
            } else {
                i + 1
            };
            let fits_empty = prefix[end] - prefix[candidate] <= max_height + FIT_TOLERANCE;
            let break_at = if candidate > column_start && fits_empty {
                candidate
            } else {
                emergency_start[i]
            };
            // Overlong words may use emergency boundaries; punctuation,
            // graphemes, explicit no-break controls and annotations may not.
            if break_at > column_start {
                column += 1;
                cursor = 0.0;
                for k in break_at..i {
                    placements[k] = (column, cursor);
                    cursor += cells[k].cell.extent;
                }
                column_start = break_at;
            }
        }
        placements.push((column, cursor));
        cursor += flow.cell.extent;
    }
    placements
}

/// Source edge characters of every cell, including sideways runs. Offsets
/// stay in UTF-16 source space; typography never inserts saved spaces.
fn pairwise_cell_edges(cells: &[FlowCell], text: &str) -> Vec<(Option<char>, Option<char>)> {
    let mut offset = 0;
    let chars: Vec<_> = text
        .chars()
        .map(|ch| {
            let at = offset;
            offset += ch.len_utf16();
            (at, ch)
        })
        .collect();
    cells
        .iter()
        .map(|flow| {
            let start = chars.partition_point(|(at, _)| *at < flow.cell.start);
            let end = chars.partition_point(|(at, _)| *at < flow.cell.end);
            (
                chars.get(start).filter(|_| start < end).map(|(_, ch)| *ch),
                end.checked_sub(1)
                    .and_then(|at| chars.get(at))
                    .filter(|_| start < end)
                    .map(|(_, ch)| *ch),
            )
        })
        .collect()
}

pub(in crate::shapes::text_vertical) fn apply_pairwise_spacing(
    cells: &mut [FlowCell],
    text: &str,
    policy: TextLayoutPolicy,
) {
    let edges = pairwise_cell_edges(cells, text);
    for i in 1..cells.len() {
        if cells[i].keep_with_previous || cells[i].shares_previous_box {
            continue;
        }
        let (Some(before), Some(after)) = (edges[i - 1].1, edges[i].0) else {
            continue;
        };
        let em = cells[i - 1].cell.font_size.min(cells[i].cell.font_size);
        let available = (cells[i - 1].cell.extent
            - cells[i - 1].trailing_spacing
            - 0.5 * cells[i - 1].cell.font_size)
            .max(0.0);
        let spacing = policy.pair_spacing_em(before, after, true);
        let compression = (spacing.compression * em).min(available);
        // A tate-chu-yoko composite sets in one ideograph's cell, so the
        // Han/Western gap of its digits or letters does not apply.
        let tate_chu_yoko =
            |flow: &FlowCell| matches!(flow.cell.kind, CellKind::TateChuYoko { .. });
        let gap = if tate_chu_yoko(&cells[i - 1]) || tate_chu_yoko(&cells[i]) {
            0.0
        } else {
            spacing.script_gap * em
        };
        cells[i - 1].cell.extent = (cells[i - 1].cell.extent + gap - compression)
            .max(cells[i - 1].trailing_spacing.max(0.0));
        cells[i - 1].cell.min_squeezed_extent = cells[i - 1].cell.extent;
    }
}

/// Justify non-final columns over policy-approved gaps with bounded amounts.
/// Korean word mode only expands spaces; grapheme and annotation interiors
/// never become opportunities merely because a font/style run split them.
pub(in crate::shapes::text_vertical) fn pairwise_aligned_tops(
    cells: &[FlowCell],
    text: &str,
    policy: TextLayoutPolicy,
    placements: &[(usize, f32)],
    align: TextAlign,
    budget: f32,
) -> Vec<f32> {
    let mut tops: Vec<f32> = placements.iter().map(|(_, top)| *top).collect();
    let edges = pairwise_cell_edges(cells, text);
    let graphemes: Vec<usize> = text
        .graphemes(true)
        .scan(0, |at, g| {
            let start = *at;
            *at += g.encode_utf16().count();
            Some(start)
        })
        .collect();
    let last_column = placements.last().map_or(0, |p| p.0);
    let mut start = 0;
    while start < cells.len() {
        let column = placements[start].0;
        let end = placements.partition_point(|p| p.0 <= column);
        let used = placements[end - 1].1 + cells[end - 1].cell.extent;
        let shift = align_offset_along_column(align, budget, used);
        for top in &mut tops[start..end] {
            *top += shift;
        }
        if (align == TextAlign::Distribute || (align == TextAlign::Justify && column < last_column))
            && is_bounded(budget)
        {
            let remaining = (budget - used).max(0.0);
            let mut extras = vec![0.0; end - start];
            let mut opportunities = Vec::new();
            for i in start..end - 1 {
                if cells[i + 1].keep_with_previous
                    || cells[i + 1].shares_previous_box
                    || graphemes.binary_search(&cells[i + 1].cell.start).is_err()
                {
                    continue;
                }
                let (Some(before), Some(after)) = (edges[i].1, edges[i + 1].0) else {
                    continue;
                };
                if align == TextAlign::Distribute
                    && matches!(before, '\u{00a0}' | '\u{202f}' | '\u{2060}')
                    || align == TextAlign::Distribute
                        && matches!(after, '\u{00a0}' | '\u{202f}' | '\u{2060}')
                {
                    continue;
                }
                let em = cells[i].cell.font_size.min(cells[i + 1].cell.font_size);
                let distribute = (align == TextAlign::Distribute).then_some(budget);
                if let Some((priority, cap)) =
                    bounded_expansion(policy, before, after, em, distribute)
                {
                    opportunities.push((i, priority, cap));
                }
            }
            for (i, amount) in allocate_bounded_expansion(&opportunities, remaining) {
                extras[i - start] += amount;
            }
            let mut extra = 0.0;
            for i in start..end {
                tops[i] += extra;
                extra += extras[i - start];
            }
        }
        start = end;
    }
    tops
}
