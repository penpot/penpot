// Column planning and JLREQ spacing along the vertical flow. The passes run
// on a paragraph's `FlowCell`s before they are placed: they adjust cell
// extents (aki, oikomi, inter-script spacing) and pick the column breaks
// (kinsoku, burasage, oidashi).

use crate::shapes::japanese::{classify, pair_rule, JapaneseClass};
use crate::shapes::kinsoku::{forbidden_at_line_end, forbidden_at_line_start};
use crate::shapes::TextAlign;

use super::layout::{CellKind, VerticalCell};

const INTER_SCRIPT_SPACING_EM: f32 = 0.25;

/// Amounts below this are treated as zero by the spacing passes.
const EPSILON: f32 = 0.0001;

/// A placeable item in the vertical flow: its extent along the column and,
/// for single-character cells, the character (used for kinsoku decisions
/// at column breaks). Rotated runs carry no character and are unsplittable.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(super) struct FlowItem {
    pub extent: f32,
    pub ch: Option<char>,
    /// This item and its predecessor came from the same source character
    /// after a length-expanding text transform, so a column break must not
    /// split them.
    pub keep_with_previous: bool,
}

#[derive(Debug, Clone, Copy)]
pub(super) enum FlowScript {
    Upright,
    Rotated {
        starts_alphanumeric: bool,
        ends_alphanumeric: bool,
    },
}

/// A cell of one paragraph awaiting column placement, with the facts the
/// spacing passes and the column planner need.
pub(super) struct FlowCell {
    pub cell: VerticalCell,
    /// The character of single-character cells; classifies the cell for aki
    /// and kinsoku.
    pub ch: Option<char>,
    pub keep_with_previous: bool,
    pub script: FlowScript,
    /// Letter-spacing included at the end of the cell's extent.
    pub trailing_spacing: f32,
}

impl FlowCell {
    pub(super) fn new(
        cell: VerticalCell,
        ch: Option<char>,
        script: FlowScript,
        trailing_spacing: f32,
    ) -> Self {
        Self {
            cell,
            ch,
            keep_with_previous: false,
            script,
            trailing_spacing,
        }
    }

    fn item(&self) -> FlowItem {
        FlowItem {
            extent: self.cell.extent,
            ch: self.ch,
            keep_with_previous: self.keep_with_previous,
        }
    }

    /// Reduce the cell to a half-em frame (plus its letter-spacing). Opening
    /// punctuation keeps its ink in the trailing half of the em, so
    /// `pull_glyph` lifts it into the compressed cell, against the preceding
    /// character. Returns false when the cell is already that narrow.
    fn shed_to_half_em(&mut self, pull_glyph: bool) -> bool {
        let target = 0.5 * self.cell.font_size + self.trailing_spacing;
        if target >= self.cell.extent {
            return false;
        }
        if pull_glyph {
            self.cell.glyph_flow_shift -= self.cell.extent - target;
        }
        self.cell.extent = target;
        true
    }
}

pub(super) fn flow_items(cells: &[FlowCell]) -> Vec<FlowItem> {
    cells.iter().map(FlowCell::item).collect()
}

/// Punctuation allowed to hang past the column bottom (ぶら下げ / burasage): the
/// ideographic and full-width comma and period. When such a mark is the item
/// that would overflow the column, it protrudes into the margin instead of
/// wrapping (itself and its predecessor) to the next column.
fn can_hang(c: char) -> bool {
    matches!(c, '、' | '。' | '，' | '．')
}

/// True when `item` would overflow a column already filled to `cursor`.
fn overflows(cursor: f32, item: &FlowItem, max_height: f32) -> bool {
    cursor > 0.0 && cursor + item.extent > max_height && !item.ch.is_some_and(can_hang)
}

/// First item of the next column when `items[i]` overflows the column that
/// starts at `column_start`. Kinsoku: a break may not leave a
/// forbidden-at-line-end character at the column bottom nor put a
/// forbidden-at-line-start character at the next column top; offending
/// predecessors move to the new column (oidashi), bounded so a pathological
/// run cannot empty its column. `None` keeps `items[i]` in the column: it
/// closes an atomic composite (group ruby or an expanded source scalar) that
/// began at the column head, which has no legal internal break.
fn column_break(
    items: &[FlowItem],
    column_start: usize,
    i: usize,
    max_height: f32,
) -> Option<usize> {
    const MAX_KINSOKU_SHIFT: usize = 4;

    let skip_kept = |mut index: usize| {
        while index > column_start && items[index].keep_with_previous {
            index -= 1;
        }
        index
    };

    let mut break_at = skip_kept(i);
    if break_at == column_start && items[i].keep_with_previous {
        return None;
    }
    let mut shifted = 0;
    while break_at > column_start
        && shifted < MAX_KINSOKU_SHIFT
        && (items[break_at].ch.is_some_and(forbidden_at_line_start)
            || items[break_at - 1].ch.is_some_and(forbidden_at_line_end))
    {
        break_at = skip_kept(break_at - 1);
        shifted += 1;
    }
    // Give up on the shift when the moved items plus the current one would
    // overflow the new column too: overflowing the wrap budget is worse than
    // the kinsoku violation.
    let shifted_extent: f32 = items[break_at..i].iter().map(|it| it.extent).sum();
    if break_at < i && shifted_extent + items[i].extent > max_height {
        break_at = i;
    }
    Some(break_at)
}

/// Assign items to columns: returns (column-index, offset-from-top) per
/// item. An item that overflows the column height starts a new column (see
/// `column_break`); items taller than the column occupy one on their own.
/// Burasage marks never overflow: the next non-hanging item still sees the
/// overflowed cursor and wraps normally, and oidashi never pulls the hung
/// marks because they are forbidden-at-line-start, not -end.
pub(super) fn plan_columns(items: &[FlowItem], max_height: f32) -> Vec<(usize, f32)> {
    let mut placements: Vec<(usize, f32)> = Vec::with_capacity(items.len());
    let mut column = 0usize;
    let mut cursor = 0.0f32;
    let mut column_start = 0usize;

    for (i, item) in items.iter().enumerate() {
        if overflows(cursor, item, max_height) {
            if let Some(break_at) = column_break(items, column_start, i, max_height) {
                column += 1;
                cursor = 0.0;
                for k in break_at..i {
                    placements[k] = (column, cursor);
                    cursor += items[k].extent;
                }
                column_start = break_at;
            }
        }
        placements.push((column, cursor));
        cursor += item.extent;
    }
    placements
}

/// True when `max_height` is a real wrap budget (auto-width columns are
/// unbounded).
pub(super) fn is_bounded(max_height: f32) -> bool {
    max_height.is_finite() && max_height > 0.0 && max_height < f32::MAX
}

/// Offset of a column's content along the column (vertical/inline) axis for
/// a given `text-align`. In `vertical-rl` the inline axis runs top->bottom, so
/// Left/Start anchor to the top, Right/End to the bottom and Center to the
/// middle of the wrap budget. `budget` is the column wrap height (the box
/// height); an unbounded budget (auto-width, columns are snug) yields no shift.
/// Justify yields no uniform shift here — its space is distributed between
/// cells by `ordered_expansion_offsets`.
pub(super) fn align_offset_along_column(align: TextAlign, budget: f32, used: f32) -> f32 {
    if !is_bounded(budget) {
        return 0.0;
    }
    let slack = (budget - used).max(0.0);
    match align {
        TextAlign::Center => slack / 2.0,
        TextAlign::Right | TextAlign::End => slack,
        _ => 0.0,
    }
}

/// Japanese inter-script spacing at an upright CJK <-> rotated alphabetic or
/// numeric boundary. Punctuation and explicit whitespace do not create an
/// automatic gap. Use the smaller adjacent font size so a large neighboring
/// run cannot create a disproportionate gap.
fn inter_script_spacing(
    previous: FlowScript,
    previous_font_size: f32,
    next: FlowScript,
    next_font_size: f32,
) -> f32 {
    let boundary = matches!(
        (previous, next),
        (
            FlowScript::Upright,
            FlowScript::Rotated {
                starts_alphanumeric: true,
                ..
            }
        ) | (
            FlowScript::Rotated {
                ends_alphanumeric: true,
                ..
            },
            FlowScript::Upright
        )
    );
    if boundary {
        previous_font_size.min(next_font_size) * INTER_SCRIPT_SPACING_EM
    } else {
        0.0
    }
}

/// Give every upright <-> Western boundary its inter-script gap. The
/// preceding cell's flow advance is adjusted so the *visible ink* edges have
/// the target gap: logical advances alone are asymmetric around mixed
/// fonts/glyphs (e.g. `うpenあ`) because their side bearings differ. The
/// preceding cell's explicit trailing letter-spacing is preserved.
pub(super) fn apply_inter_script_spacing(cells: &mut [FlowCell]) {
    for i in 1..cells.len() {
        let target_gap = inter_script_spacing(
            cells[i - 1].script,
            cells[i - 1].cell.font_size,
            cells[i].script,
            cells[i].cell.font_size,
        );
        if target_gap <= 0.0 {
            continue;
        }
        let previous = &cells[i - 1];
        let natural_gap = previous.cell.extent - previous.trailing_spacing + cells[i].cell.ink_top
            - previous.cell.ink_bottom;
        let corrected = previous.cell.extent + target_gap - natural_gap;
        cells[i - 1].cell.extent = corrected.max(0.0);
    }
}

/// JLREQ character class of each cell. Ruby bases count as simple ruby, so
/// `ruby_spans[span]` overrides the cell's own class.
pub(super) fn flow_classes(cells: &[FlowCell], ruby_spans: &[bool]) -> Vec<Option<JapaneseClass>> {
    cells
        .iter()
        .map(|flow| {
            if ruby_spans[flow.cell.span] {
                return Some(JapaneseClass::SimpleRuby);
            }
            match flow.cell.kind {
                CellKind::TateChuYoko { .. } => Some(JapaneseClass::TateChuYoko),
                CellKind::Rotated { .. } => Some(JapaneseClass::Western),
                _ => flow.ch.map(classify),
            }
        })
        .collect()
}

fn embedded_leading_aki(class: JapaneseClass) -> f32 {
    match class {
        JapaneseClass::OpeningBracket => 0.5,
        JapaneseClass::MiddleDot => 0.25,
        _ => 0.0,
    }
}

fn embedded_trailing_aki(class: JapaneseClass) -> f32 {
    match class {
        JapaneseClass::ClosingBracket | JapaneseClass::FullStop | JapaneseClass::Comma => 0.5,
        JapaneseClass::MiddleDot => 0.25,
        _ => 0.0,
    }
}

/// JLREQ punctuation and cl-30 adjacency. Full-width fonts bake the half-em
/// aki into punctuation advances. That preferred aki stays in ordinary text,
/// but one half-em goes at the internal boundaries defined by §3.1.4:
/// closing punctuation sequences set solid, closing→opening retains a single
/// half-em, opening sequences set solid after the first bracket, and
/// middle-dot adjacency retains its own quarter-em side spacing.
pub(super) fn shed_punctuation_aki(cells: &mut [FlowCell], classes: &[Option<JapaneseClass>]) {
    for (i, flow) in cells.iter_mut().enumerate() {
        let Some(ch) = flow.ch else {
            continue;
        };
        let class = classify(ch);
        let closing = class.is_trailing_aki_punctuation();
        let opening = class == JapaneseClass::OpeningBracket;
        let shed = if closing {
            classes.get(i + 1).copied().flatten().is_some_and(|next| {
                0.5 + embedded_leading_aki(next)
                    > pair_rule(class, next).preferred_em + f32::EPSILON
            })
        } else if opening {
            i.checked_sub(1)
                .and_then(|previous| classes[previous])
                .is_some_and(|previous| {
                    // A preceding trailing-aki mark owns the reduction for
                    // closing→opening, so never remove both halves.
                    !previous.is_trailing_aki_punctuation()
                        && embedded_trailing_aki(previous) + 0.5
                            > pair_rule(previous, class).preferred_em + f32::EPSILON
                })
        } else {
            false
        };
        if shed {
            flow.shed_to_half_em(opening);
        }
    }
}

/// Smallest legal flow extent after oikomi. Full-width Japanese punctuation
/// normally carries removable aki inside its one-em advance, but `vpal` may
/// have removed that aki already. Clamp the floor to the actual shaped extent
/// so a proportional half-em glyph frame can never be compressed again.
pub(super) fn minimum_oikomi_extent(
    ch: Option<char>,
    extent: f32,
    font_size: f32,
    letter_spacing: f32,
) -> f32 {
    let half_em_frame = ch.is_some_and(|ch| {
        matches!(
            classify(ch),
            JapaneseClass::OpeningBracket
                | JapaneseClass::ClosingBracket
                | JapaneseClass::FullStop
                | JapaneseClass::Comma
                | JapaneseClass::MiddleDot
        )
    });
    if half_em_frame {
        extent.min(0.5 * font_size + letter_spacing)
    } else {
        extent
    }
}

/// Divide `amount` equally across capped opportunities, redistributing the
/// remainder whenever one opportunity reaches its cap.
fn capped_equal_allocations(capacities: &[(usize, f32)], amount: f32) -> Vec<(usize, f32)> {
    let mut allocations: Vec<(usize, f32)> = capacities.iter().map(|(i, _)| (*i, 0.0)).collect();
    let mut remaining = amount.max(0.0);
    while remaining > EPSILON {
        let active: Vec<usize> = capacities
            .iter()
            .enumerate()
            .filter_map(|(slot, (_, cap))| (allocations[slot].1 + EPSILON < *cap).then_some(slot))
            .collect();
        if active.is_empty() {
            break;
        }
        let share = remaining / active.len() as f32;
        let mut used = 0.0;
        for slot in active {
            let cap = capacities[slot].1;
            let delta = share.min(cap - allocations[slot].1);
            allocations[slot].1 += delta;
            used += delta;
        }
        if used <= EPSILON {
            break;
        }
        remaining -= used;
    }
    allocations
}

/// The em that scales the spacing between `cells[boundary]` and the next
/// cell: the smaller adjacent font size.
fn boundary_em(cells: &[FlowCell], boundary: usize) -> f32 {
    cells[boundary]
        .cell
        .font_size
        .min(cells[boundary + 1].cell.font_size)
}

fn explicit_pair_spacing_em(before: JapaneseClass, after: JapaneseClass) -> f32 {
    if matches!(before, JapaneseClass::DividingPunctuation) {
        pair_rule(before, after).preferred_em
    } else {
        0.0
    }
}

/// Adjacent classes at `boundary`, when both cells have one.
fn boundary_classes(
    classes: &[Option<JapaneseClass>],
    boundary: usize,
) -> Option<(JapaneseClass, JapaneseClass)> {
    Some((classes[boundary]?, classes[boundary + 1]?))
}

/// Preferred pair spacing (in em) at every boundary, per the JLREQ table.
pub(super) fn preferred_pair_spacing(classes: &[Option<JapaneseClass>]) -> Vec<f32> {
    (0..classes.len().saturating_sub(1))
        .map(|boundary| {
            boundary_classes(classes, boundary)
                .map_or(0.0, |(before, after)| pair_rule(before, after).preferred_em)
        })
        .collect()
}

/// cl-04 question/exclamation marks carry an explicit one-em space after
/// their character frame. Unlike bracket/comma/full-stop aki, this space is
/// not embedded in the font's full-width advance, so it must exist in the
/// flow extent before oikomi is allowed to reduce it.
pub(super) fn materialize_explicit_pair_spacing(
    cells: &mut [FlowCell],
    classes: &[Option<JapaneseClass>],
) {
    for boundary in 0..cells.len().saturating_sub(1) {
        let Some((before, after)) = boundary_classes(classes, boundary) else {
            continue;
        };
        let spacing_em = explicit_pair_spacing_em(before, after);
        if spacing_em > 0.0 {
            cells[boundary].cell.extent += spacing_em * boundary_em(cells, boundary);
        }
    }
}

/// Cell holding the removable spacing of a boundary, and whether that space
/// leads the cell (its glyph then moves up with the reduction).
fn spacing_owner(before: JapaneseClass, after: JapaneseClass, boundary: usize) -> (usize, bool) {
    if explicit_pair_spacing_em(before, after) > 0.0 {
        (boundary, false)
    } else if matches!(
        after,
        JapaneseClass::OpeningBracket | JapaneseClass::MiddleDot
    ) {
        // Sequence layout removes any redundant preceding trailing half; the
        // remaining aki is the next glyph's embedded leading space.
        (boundary + 1, true)
    } else {
        (boundary, false)
    }
}

/// Remove up to `amount` of removable spacing from `cells[index]`, never
/// below its oikomi floor. Returns the amount removed.
fn reduce_cell_spacing(cells: &mut [FlowCell], index: usize, amount: f32, leading: bool) -> f32 {
    let cell = &mut cells[index].cell;
    let removable = (cell.extent - cell.minimum_oikomi_extent).max(0.0);
    let amount = amount.min(cell.extent.max(0.0)).min(removable);
    cell.extent -= amount;
    if leading {
        cell.glyph_flow_shift -= amount;
    }
    amount
}

/// Explicit spacing at a column edge is discarded (no trailing `！` space at
/// the column bottom). Returns true when any spacing was removed.
fn discard_explicit_spacing_at_column_edges(
    cells: &mut [FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &mut [f32],
    placements: &[(usize, f32)],
) -> bool {
    let mut changed = false;
    for boundary in 0..cells.len().saturating_sub(1) {
        if placements[boundary].0 == placements[boundary + 1].0 {
            continue;
        }
        let Some((before, after)) = boundary_classes(classes, boundary) else {
            continue;
        };
        if explicit_pair_spacing_em(before, after) <= 0.0 {
            continue;
        }
        let spacing = pair_spacing_em[boundary] * boundary_em(cells, boundary);
        if spacing <= 0.0 {
            continue;
        }
        reduce_cell_spacing(cells, boundary, spacing, false);
        pair_spacing_em[boundary] = 0.0;
        changed = true;
    }
    changed
}

/// JLREQ oikomi: before wrapping a non-hanging item, try to keep it in the
/// current column by reducing only legal aki, in table priority order. If the
/// complete deficit cannot be recovered, leave the line untouched for the
/// subsequent oidashi/kinsoku planner.
pub(super) fn apply_ordered_oikomi(
    cells: &mut [FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &mut [f32],
    max_height: f32,
) {
    if !is_bounded(max_height) {
        return;
    }
    let mut column_start = 0usize;
    let mut cursor = 0.0f32;
    for i in 0..cells.len() {
        if overflows(cursor, &cells[i].item(), max_height) {
            let deficit = cursor + cells[i].cell.extent - max_height;
            cursor -= compress_line(cells, classes, pair_spacing_em, column_start..i, deficit);
            if cursor + cells[i].cell.extent > max_height + EPSILON {
                column_start = i;
                cursor = 0.0;
            }
        }
        cursor += cells[i].cell.extent;
    }
}

/// Recover `deficit` from the boundaries in `line` (the cells before the
/// overflowing one), in shrink-priority order, when the whole deficit fits.
/// Returns the extent removed from the line.
fn compress_line(
    cells: &mut [FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &mut [f32],
    line: std::ops::Range<usize>,
    deficit: f32,
) -> f32 {
    let overflowing = line.end;
    // (boundary, priority, capacity)
    let mut opportunities: Vec<(usize, u8, f32)> = Vec::new();
    for boundary in line {
        let Some((before, after)) = boundary_classes(classes, boundary) else {
            continue;
        };
        let rule = pair_rule(before, after);
        if rule.shrink_priority == 0 || pair_spacing_em[boundary] <= rule.minimum_em {
            continue;
        }
        let em = boundary_em(cells, boundary);
        let (owner, _) = spacing_owner(before, after, boundary);
        let owner = &cells[owner].cell;
        let rule_capacity = (pair_spacing_em[boundary] - rule.minimum_em) * em;
        let physical_capacity = (owner.extent - owner.minimum_oikomi_extent).max(0.0);
        let capacity = rule_capacity.min(physical_capacity);
        if capacity > EPSILON {
            opportunities.push((boundary, rule.shrink_priority, capacity));
        }
    }
    let total_capacity: f32 = opportunities.iter().map(|(_, _, cap)| *cap).sum();
    if total_capacity + EPSILON < deficit {
        return 0.0;
    }

    let mut removed_from_line = 0.0;
    let mut remaining = deficit;
    for priority in 1..=5 {
        if remaining <= EPSILON {
            break;
        }
        let caps: Vec<(usize, f32)> = opportunities
            .iter()
            .filter(|(_, p, _)| *p == priority)
            .map(|(boundary, _, cap)| (*boundary, *cap))
            .collect();
        if caps.is_empty() {
            continue;
        }
        let available: f32 = caps.iter().map(|(_, cap)| *cap).sum();
        for (boundary, reduction) in capped_equal_allocations(&caps, remaining.min(available)) {
            let Some((before, after)) = boundary_classes(classes, boundary) else {
                continue;
            };
            let em = boundary_em(cells, boundary);
            let (owner, leading) = spacing_owner(before, after, boundary);
            let reduction = reduce_cell_spacing(cells, owner, reduction, leading);
            pair_spacing_em[boundary] -= reduction / em;
            if owner < overflowing {
                removed_from_line += reduction;
            }
            remaining -= reduction;
        }
    }
    removed_from_line
}

/// Plan the columns, then trim spacing that a break left at a column edge
/// and re-plan, since a shorter line may pull one more cell into the
/// preceding column. Each round only shrinks a previously untrimmed cell, so
/// the loops are bounded by the cell count.
///
/// Wrapped opening brackets use the JIS X 4051 tentsuki policy: their leading
/// half-em is discarded at a column head.
pub(super) fn plan_with_edge_trimming(
    cells: &mut [FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &mut [f32],
    max_height: f32,
) -> Vec<(usize, f32)> {
    let mut placements = plan_columns(&flow_items(cells), max_height);
    for _ in 0..cells.len() {
        if !discard_explicit_spacing_at_column_edges(cells, classes, pair_spacing_em, &placements) {
            break;
        }
        placements = plan_columns(&flow_items(cells), max_height);
    }
    for _ in 0..cells.len() {
        let mut changed = false;
        for (flow, (_, top)) in cells.iter_mut().zip(&placements) {
            let opening = flow
                .ch
                .is_some_and(|ch| classify(ch) == JapaneseClass::OpeningBracket);
            if *top == 0.0 && opening {
                changed |= flow.shed_to_half_em(true);
            }
        }
        if !changed {
            break;
        }
        placements = plan_columns(&flow_items(cells), max_height);
    }
    placements
}

/// Ordered oidashi expansion for justified columns. Stages 1–3 respect the
/// table caps; if slack remains, stage 4 distributes it equally across every
/// otherwise-expandable boundary, as required by JLREQ §3.8.4. Returns the
/// extra flow offset of every cell. The last column is not justified.
pub(super) fn ordered_expansion_offsets(
    cells: &[FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &[f32],
    placements: &[(usize, f32)],
    column_used: &[f32],
    max_height: f32,
) -> Vec<f32> {
    let last_column = column_used.len().saturating_sub(1);
    let mut boundary_expansion = vec![0.0f32; cells.len().saturating_sub(1)];
    for (column, used) in column_used.iter().enumerate().take(last_column) {
        // (boundary, priority, cap)
        let mut boundaries: Vec<(usize, u8, f32)> = Vec::new();
        for boundary in 0..cells.len().saturating_sub(1) {
            if placements[boundary].0 != column || placements[boundary + 1].0 != column {
                continue;
            }
            let Some((before, after)) = boundary_classes(classes, boundary) else {
                continue;
            };
            let rule = pair_rule(before, after);
            if rule.expand_priority == 0 {
                continue;
            }
            let cap = (rule.maximum_em - pair_spacing_em[boundary]).max(0.0)
                * boundary_em(cells, boundary);
            boundaries.push((boundary, rule.expand_priority, cap));
        }
        let mut remaining = (max_height - used).max(0.0);
        for priority in 1..=3 {
            let caps: Vec<(usize, f32)> = boundaries
                .iter()
                .filter(|(_, p, _)| *p == priority)
                .map(|(boundary, _, cap)| (*boundary, *cap))
                .collect();
            let available: f32 = caps.iter().map(|(_, cap)| *cap).sum();
            for (boundary, expansion) in capped_equal_allocations(&caps, remaining.min(available)) {
                boundary_expansion[boundary] += expansion;
                remaining -= expansion;
            }
        }
        if remaining > EPSILON && !boundaries.is_empty() {
            let extra = remaining / boundaries.len() as f32;
            for (boundary, _, _) in &boundaries {
                boundary_expansion[*boundary] += extra;
            }
        }
    }

    let mut offsets = vec![0.0f32; cells.len()];
    for i in 1..cells.len() {
        if placements[i].0 == placements[i - 1].0 {
            offsets[i] = offsets[i - 1] + boundary_expansion[i - 1];
        }
    }
    offsets
}

#[cfg(test)]
mod tests {
    use super::super::layout::{wrap_height, CellKind, VerticalCell};
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::{FontFeatures, GrowType, TextCombineUpright, TextSpan};

    fn item(extent: f32, ch: char) -> FlowItem {
        FlowItem {
            extent,
            ch: Some(ch),
            keep_with_previous: false,
        }
    }

    fn aligned_content(text: &str, height: f32, align: TextAlign) -> crate::shapes::TextContent {
        content_of(
            vec![vertical_paragraph(vec![make_span(text)], align, 1.0)],
            height,
            GrowType::Fixed,
        )
    }

    fn vpal_content(text: &str) -> crate::shapes::TextContent {
        spans_content(
            vec![TextSpan {
                font_features: FontFeatures::Vpal,
                ..make_span(text)
            }],
            1000.0,
        )
    }

    fn visible_flow_gap(previous: &VerticalCell, next: &VerticalCell) -> f32 {
        next.top + next.ink_top - (previous.top + previous.ink_bottom)
    }

    #[test]
    fn plan_columns_breaks_on_overflow() {
        let items: Vec<FlowItem> = "あいうえお".chars().map(|c| item(10.0, c)).collect();
        let placements = plan_columns(&items, 25.0);
        assert_eq!(
            placements,
            vec![(0, 0.0), (0, 10.0), (1, 0.0), (1, 10.0), (2, 0.0)]
        );
    }

    #[test]
    fn plan_columns_oversized_item_gets_own_column() {
        let items = vec![item(10.0, 'あ'), item(100.0, 'い'), item(10.0, 'う')];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (1, 0.0), (2, 0.0)]);
    }

    #[test]
    fn plan_columns_burasage_hangs_comma_period() {
        // 。 overflows the two-cell column; instead of oidashi (pushing い down
        // with it), burasage lets it hang past the column bottom. The invariant
        // "no forbidden-at-line-start char at a column top" still holds — 。
        // never reaches the next column's top.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, 'い'),
            item(10.0, '。'),
            item(10.0, 'う'),
        ];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (0, 10.0), (0, 20.0), (1, 0.0)]);
        assert_eq!(placements[2].0, 0, "。 hangs in the current column");
    }

    #[test]
    fn plan_columns_kinsoku_no_forbidden_end_at_column_bottom() {
        // 「 would be left at the bottom of column 0; it moves down.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, '「'),
            item(10.0, 'い'),
            item(10.0, 'う'),
        ];
        let placements = plan_columns(&items, 25.0);
        assert_eq!(placements, vec![(0, 0.0), (1, 0.0), (1, 10.0), (2, 0.0)]);
    }

    #[test]
    fn plan_columns_kinsoku_shift_is_bounded() {
        // A column full of open brackets cannot be emptied: oidashi moves at
        // most four of them along with the overflowing あ.
        let items: Vec<FlowItem> = "「「「「「「あ".chars().map(|c| item(10.0, c)).collect();
        let placements = plan_columns(&items, 60.0);
        let first_column_count = placements.iter().filter(|(c, _)| *c == 0).count();
        assert_eq!(first_column_count, 2);
    }

    #[test]
    fn plan_columns_kinsoku_shift_never_overflows_budget() {
        // A two-item budget: moving the trailing 「「 down with the
        // overflowing い would put three items (30.0) in a 20.0 column;
        // the shift is dropped instead, keeping every column within the
        // wrap budget.
        let items = vec![
            item(10.0, 'あ'),
            item(10.0, '「'),
            item(10.0, '「'),
            item(10.0, 'い'),
        ];
        let placements = plan_columns(&items, 20.0);
        let mut column_used = std::collections::BTreeMap::new();
        for (it, (column, top)) in items.iter().zip(&placements) {
            let used: &mut f32 = column_used.entry(*column).or_default();
            *used = used.max(top + it.extent);
        }
        for (column, used) in column_used {
            assert!(used <= 20.0, "column {column} overflows: {used}");
        }
    }

    #[test]
    fn capped_adjustment_redistributes_after_a_boundary_saturates() {
        let allocations = capped_equal_allocations(&[(0, 1.0), (1, 3.0)], 3.0);
        assert_eq!(allocations, vec![(0, 1.0), (1, 2.0)]);
    }

    #[test]
    fn align_offset_helper_maps_edges() {
        // Unbounded budget (auto-width): no shift.
        assert_eq!(
            align_offset_along_column(TextAlign::Center, f32::MAX, 40.0),
            0.0
        );
        // Left/Start anchors to the top.
        assert_eq!(align_offset_along_column(TextAlign::Left, 100.0, 40.0), 0.0);
        // Center splits the slack.
        assert_eq!(
            align_offset_along_column(TextAlign::Center, 100.0, 40.0),
            30.0
        );
        // Right/End pushes to the bottom.
        assert_eq!(
            align_offset_along_column(TextAlign::Right, 100.0, 40.0),
            60.0
        );
        assert_eq!(align_offset_along_column(TextAlign::End, 100.0, 40.0), 60.0);
        // Overfull column: no negative shift.
        assert_eq!(
            align_offset_along_column(TextAlign::Right, 40.0, 100.0),
            0.0
        );
    }

    #[test]
    fn layout_kinsoku_no_period_at_column_top() {
        // Wrap height fits exactly 2 characters per column; the 。 after
        // the second character would start column 2 without kinsoku.
        let text = "あい。うえお";
        let content = make_content(&[text], 100.0);
        let cell_extent = layout_content(&content, 1000.0).cells[0].extent;
        let layout = layout_content(&content, cell_extent * 2.0 + 0.1);

        assert!(layout.columns.len() > 1, "the text must wrap");
        let chars: Vec<char> = text.chars().collect();
        for column_index in 0..layout.columns.len() {
            let head = layout
                .cells
                .iter()
                .filter(|c| c.column == column_index)
                .min_by(|a, b| a.top.total_cmp(&b.top))
                .expect("every column holds a cell");
            let c = chars[head.start];
            assert!(
                !forbidden_at_line_start(c),
                "column {column_index} starts with forbidden char {c}"
            );
        }
    }

    #[test]
    fn ordinary_closing_punctuation_keeps_preferred_aki() {
        // In ordinary text, the comma and closing bracket keep their normal
        // half-em glyph body plus half-em trailing aki: one em in total.
        let content = make_content(&["く、く」く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        assert_eq!(layout.cells.len(), 5, "one cell per character");
        let em = 20.0;
        assert!(
            (layout.cells[0].extent - em).abs() < 1.0,
            "leading ideograph keeps full advance, got {}",
            layout.cells[0].extent
        );
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "、 before an ideograph keeps its aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[3].extent - em).abs() < 1.0,
            "」 before an ideograph keeps its aki, got {}",
            layout.cells[3].extent
        );
        assert!(
            (layout.cells[4].extent - em).abs() < 1.0,
            "trailing ideograph keeps full advance, got {}",
            layout.cells[4].extent
        );
    }

    #[test]
    fn closing_then_opening_keeps_half_em_aki() {
        // く」「く: the closing bracket sheds its trailing half, but the
        // opening bracket after it keeps its full em (leading half blank), so
        // the pair keeps the half-em aki JIS X 4051 asks for instead of
        // setting solid.
        let content = make_content(&["く」「く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        assert_eq!(layout.cells.len(), 4, "one cell per character");
        let em = 20.0;
        assert!(
            layout.cells[1].extent <= 0.5 * em + 0.01,
            "」 sheds its trailing aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "「 after a closing mark keeps its full em, got {}",
            layout.cells[2].extent
        );
        assert_eq!(
            layout.cells[2].glyph_flow_shift, 0.0,
            "the unshed opening bracket is not shifted"
        );
    }

    #[test]
    fn dividing_punctuation_materializes_trailing_aki() {
        let provider = provider(VMTX_TEST_FONT);
        let terminal = layout_with(&provider, &make_content(&["！"], 1000.0));
        let before_ideograph = layout_with(&provider, &make_content(&["！く"], 1000.0));
        let before_dividing = layout_with(&provider, &make_content(&["！？"], 1000.0));
        let before_closing = layout_with(&provider, &make_content(&["！」"], 1000.0));
        let em = 20.0;
        let natural_extent = terminal.cells[0].extent;

        assert!(
            (before_ideograph.cells[0].extent - natural_extent - em).abs() < 0.01,
            "！ before ordinary text adds one em, got {} over natural {natural_extent}",
            before_ideograph.cells[0].extent
        );
        assert!(
            (before_dividing.cells[0].extent - natural_extent - em).abs() < 0.01,
            "！ before ？ adds one em, got {} over natural {natural_extent}",
            before_dividing.cells[0].extent
        );
        assert!(
            (before_closing.cells[0].extent - natural_extent).abs() < 0.01,
            "！ before a closing bracket adds no aki, got {} over natural {natural_extent}",
            before_closing.cells[0].extent
        );
    }

    #[test]
    fn dividing_punctuation_oikomi_never_compresses_glyph_frames() {
        let provider = provider(VMTX_TEST_FONT);
        let terminal_exclamation = layout_with(&provider, &make_content(&["！"], 1000.0));
        let terminal_question = layout_with(&provider, &make_content(&["？"], 1000.0));
        let layout = layout_with_height(&provider, &make_content(&["く！？く"], 60.0), 60.0);

        assert!(
            layout.cells[1].extent + 0.01 >= terminal_exclamation.cells[0].extent,
            "oikomi compressed the ！ glyph frame from {} to {}",
            terminal_exclamation.cells[0].extent,
            layout.cells[1].extent
        );
        assert!(
            layout.cells[2].extent + 0.01 >= terminal_question.cells[0].extent,
            "oikomi compressed the ？ glyph frame from {} to {}",
            terminal_question.cells[0].extent,
            layout.cells[2].extent
        );
    }

    #[test]
    fn dividing_punctuation_discards_trailing_aki_at_column_edge() {
        let provider = provider(VMTX_TEST_FONT);
        let terminal = layout_with(&provider, &make_content(&["！"], 1000.0));
        let layout = layout_with_height(&provider, &make_content(&["！く"], 20.0), 20.0);

        assert_ne!(layout.cells[0].column, layout.cells[1].column);
        assert!(
            (layout.cells[0].extent - terminal.cells[0].extent).abs() < 0.01,
            "column-end ！ should discard its trailing aki, got {} over natural {}",
            layout.cells[0].extent,
            terminal.cells[0].extent
        );
    }

    #[test]
    fn ordinary_opening_punctuation_keeps_preferred_aki() {
        // く「く: the opening bracket keeps its leading half-em aki in the
        // middle of a line, so its total advance remains one em.
        let content = make_content(&["く「く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        assert_eq!(layout.cells.len(), 3, "one cell per character");
        let em = 20.0;
        assert!(
            (layout.cells[0].extent - em).abs() < 1.0,
            "leading ideograph keeps full advance, got {}",
            layout.cells[0].extent
        );
        assert_eq!(
            layout.cells[0].glyph_flow_shift, 0.0,
            "ideograph is not shifted"
        );
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "「 keeps its leading aki, got {}",
            layout.cells[1].extent
        );
        assert_eq!(
            layout.cells[1].glyph_flow_shift, 0.0,
            "an uncompressed opening bracket is not shifted, got {}",
            layout.cells[1].glyph_flow_shift
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "trailing ideograph keeps full advance, got {}",
            layout.cells[2].extent
        );
    }

    #[test]
    fn consecutive_closing_punctuation_sets_solid_internally() {
        let content = make_content(&["く。」く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let em = 20.0;
        assert!(
            layout.cells[1].extent <= 0.5 * em + 0.01,
            "。 sheds its internal trailing aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            (layout.cells[2].extent - em).abs() < 1.0,
            "the final 」 keeps the sequence's trailing aki, got {}",
            layout.cells[2].extent
        );
    }

    #[test]
    fn consecutive_opening_punctuation_sets_solid_after_first() {
        let content = make_content(&["く「『く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let em = 20.0;
        assert!(
            (layout.cells[1].extent - em).abs() < 1.0,
            "the first opening bracket keeps the sequence's leading aki, got {}",
            layout.cells[1].extent
        );
        assert!(
            layout.cells[2].extent <= 0.5 * em + 0.01,
            "the second opening bracket sets solid, got {}",
            layout.cells[2].extent
        );
        assert!(layout.cells[2].glyph_flow_shift < -0.01);
    }

    #[test]
    fn opening_bracket_at_column_head_is_tentsuki() {
        let content = make_content(&["「く"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let em = 20.0;
        assert_eq!(layout.cells[0].top, 0.0);
        assert!(
            layout.cells[0].extent <= 0.5 * em + 0.01,
            "line-head 「 sheds its leading aki, got {}",
            layout.cells[0].extent
        );
        assert!(layout.cells[0].glyph_flow_shift < -0.01);
    }

    #[test]
    fn line_end_punctuation_keeps_preferred_half_em_aki() {
        let content = make_content(&["く、"], 40.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        assert_eq!(layout.cells[0].column, layout.cells[1].column);
        assert!(
            (layout.cells[1].extent - 20.0).abs() < 1.0,
            "line-end 、 keeps a half-em after its glyph, got {}",
            layout.cells[1].extent
        );
    }

    #[test]
    fn plain_ideographs_keep_full_advance() {
        let content = make_content(&["くくく"], 1000.0);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        for cell in &layout.cells {
            assert!(
                (cell.extent - 20.0).abs() < 1.0,
                "no punctuation: full advance kept, got {}",
                cell.extent
            );
        }
    }

    #[test]
    fn vpal_punctuation_is_not_double_compressed() {
        // 、's vertical alternate already halves its advance under vpal;
        // the aki shed must not compress the half-width cell again.
        let provider = provider(VPAL_TEST_FONT);
        let layout = layout_with(&provider, &vpal_content("あ、あ"));
        assert!(
            (layout.cells[1].extent - 10.0).abs() < 0.01,
            "、 is exactly half-width under vpal, got {}",
            layout.cells[1].extent
        );
    }

    #[test]
    fn vpal_oikomi_preserves_half_em_punctuation_frame() {
        // vpal has already removed the opening bracket's leading aki. A tight
        // fixed-height column must wrap instead of offering that same half-em
        // to oikomi and collapsing the glyph frame a second time.
        let provider = provider(VPAL_TEST_FONT);
        let content = vpal_content("あ「あ");
        let natural = layout_with(&provider, &content);
        let natural_total: f32 = natural.cells.iter().map(|cell| cell.extent).sum();
        let bracket_extent = natural.cells[1].extent;
        let constrained = layout_with_height(&provider, &content, natural_total - 5.0);

        assert!(
            (bracket_extent - 10.0).abs() < 0.01,
            "vpal opening bracket starts at half-em, got {bracket_extent}"
        );
        assert!(
            (constrained.cells[1].extent - bracket_extent).abs() < 0.01,
            "oikomi collapsed the vpal bracket frame from {bracket_extent} to {}",
            constrained.cells[1].extent
        );
        assert_ne!(
            constrained.cells[0].column, constrained.cells[2].column,
            "insufficient removable aki should wrap the protected bracket and following glyph"
        );
    }

    #[test]
    fn tate_chu_yoko_all_uses_asymmetric_punctuation_aki() {
        // 」→TCY keeps the closing mark's trailing half-em, and TCY→「 keeps
        // the opening mark's leading half-em. TCY on the opposite side of
        // either bracket sets solid.
        let mut content = make_content_with_spans(&["く", "」", "20", "「", "く"], 400.0);
        content.paragraphs_mut()[0].children_mut()[2]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        assert!(matches!(layout.cells[2].kind, CellKind::TateChuYoko { .. }));
        for (index, label) in [(1, "」 before TCY"), (2, "TCY"), (3, "「 after TCY")] {
            assert!(
                (layout.cells[index].extent - 20.0).abs() < 0.01,
                "{label} should occupy its preferred one-em frame, got {}",
                layout.cells[index].extent
            );
        }

        let mut reverse = make_content_with_spans(&["く", "「", "20", "」", "く"], 400.0);
        reverse.paragraphs_mut()[0].children_mut()[2]
            .set_text_combine_upright(TextCombineUpright::All);
        let reverse = layout_with(&provider(VMTX_TEST_FONT), &reverse);
        assert!(matches!(
            reverse.cells[2].kind,
            CellKind::TateChuYoko { .. }
        ));
        assert!((reverse.cells[1].extent - 20.0).abs() < 0.01);
        assert!((reverse.cells[3].extent - 20.0).abs() < 0.01);
    }

    #[test]
    fn tate_chu_yoko_digits_uses_the_same_cl30_adjacency() {
        let mut content = make_content_with_spans(&["く」31「く"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::Digits);
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let tcy_index = layout
            .cells
            .iter()
            .position(|cell| matches!(cell.kind, CellKind::TateChuYoko { .. }))
            .expect("digit TCY cell");
        assert!((layout.cells[tcy_index - 1].extent - 20.0).abs() < 0.01);
        assert!((layout.cells[tcy_index].extent - 20.0).abs() < 0.01);
        assert!((layout.cells[tcy_index + 1].extent - 20.0).abs() < 0.01);
    }

    #[test]
    fn inter_script_spacing_separates_cjk_and_latin() {
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM;

        let cjk_latin = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        assert_eq!(cjk_latin.cells.len(), 2);
        assert!(
            (visible_flow_gap(&cjk_latin.cells[0], &cjk_latin.cells[1]) - expected_gap).abs()
                < 0.01
        );

        let latin_cjk = layout_content(&make_content(&["aあ"], 1000.0), 1000.0);
        assert_eq!(latin_cjk.cells.len(), 2);
        assert!(
            (visible_flow_gap(&latin_cjk.cells[0], &latin_cjk.cells[1]) - expected_gap).abs()
                < 0.01
        );
    }

    #[test]
    fn inter_script_spacing_is_visually_symmetric_around_latin_run() {
        let layout = layout_content(&make_content(&["うpenあ"], 1000.0), 1000.0);
        assert_eq!(layout.cells.len(), 3);
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM;
        let before = visible_flow_gap(&layout.cells[0], &layout.cells[1]);
        let after = visible_flow_gap(&layout.cells[1], &layout.cells[2]);
        assert!((before - expected_gap).abs() < 0.01);
        assert!((after - expected_gap).abs() < 0.01);
        assert!((before - after).abs() < 0.01);
    }

    #[test]
    fn inter_script_spacing_preserves_explicit_letter_spacing() {
        let letter_spacing = 3.0;
        let layout = layout_content(&spaced_content("うpenあ", letter_spacing), 1000.0);
        assert_eq!(layout.cells.len(), 3);
        let expected_gap = 20.0 * INTER_SCRIPT_SPACING_EM + letter_spacing;
        assert!((visible_flow_gap(&layout.cells[0], &layout.cells[1]) - expected_gap).abs() < 0.01);
        assert!((visible_flow_gap(&layout.cells[1], &layout.cells[2]) - expected_gap).abs() < 0.01);
    }

    #[test]
    fn inter_script_spacing_crosses_span_boundaries() {
        let joined = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        let split = layout_content(&make_content_with_spans(&["あ", "a"], 1000.0), 1000.0);
        assert_eq!(joined.cells.len(), split.cells.len());
        for (joined, split) in joined.cells.iter().zip(&split.cells) {
            assert!((joined.top - split.top).abs() < 0.01);
            assert!((joined.extent - split.extent).abs() < 0.01);
        }
    }

    #[test]
    fn inter_script_spacing_excludes_punctuation_and_whitespace() {
        let cjk_extent = layout_content(&make_content(&["あ"], 1000.0), 1000.0).cells[0].extent;
        for text in ["あ.", "あ a"] {
            let layout = layout_content(&make_content(&[text], 1000.0), 1000.0);
            assert!(
                (layout.cells[0].extent - cjk_extent).abs() < 0.01,
                "{text:?} must not add spacing immediately after the CJK cell"
            );
        }
    }

    #[test]
    fn oikomi_reduces_script_gap_before_oidashi() {
        let natural = layout_content(&make_content(&["あa"], 1000.0), 1000.0);
        assert_eq!(natural.cells.len(), 2);
        let natural_total: f32 = natural.cells.iter().map(|cell| cell.extent).sum();
        let budget = natural_total - 2.0;
        let adjusted = layout_content(&make_content(&["あa"], budget), budget);
        assert_eq!(adjusted.cells[0].column, adjusted.cells[1].column);
        let gap = visible_flow_gap(&adjusted.cells[0], &adjusted.cells[1]);
        assert!(
            (gap - (20.0 * INTER_SCRIPT_SPACING_EM - 2.0)).abs() < 0.01,
            "oikomi should recover the two-pixel deficit from the script gap, got {gap}"
        );
    }

    #[test]
    fn oikomi_preserves_sentence_final_full_stop_aki() {
        let natural = layout_content(&make_content(&["あ。あ"], 1000.0), 1000.0);
        let natural_total: f32 = natural.cells.iter().map(|cell| cell.extent).sum();
        let budget = natural_total - 2.0;
        let adjusted = layout_content(&make_content(&["あ。あ"], budget), budget);
        assert_ne!(
            adjusted.cells[1].column, adjusted.cells[2].column,
            "fixed full-stop aki must not be compressed to retain the next ideograph"
        );
        assert!((adjusted.cells[1].extent - natural.cells[1].extent).abs() < 0.01);
    }

    #[test]
    fn text_align_shifts_column_cells() {
        let text = "あいう";
        // Baseline (top-aligned) used length of the single column.
        let used = {
            let content = aligned_content(text, 1000.0, TextAlign::Left);
            let layout = layout_content(&content, 1000.0);
            layout
                .cells
                .iter()
                .map(|c| c.top + c.extent)
                .fold(0.0f32, f32::max)
        };

        let top_of = |align: TextAlign| {
            let content = aligned_content(text, 1000.0, align);
            let layout = layout_content(&content, 1000.0);
            layout.cells[0].top
        };

        assert!(top_of(TextAlign::Left).abs() < 0.01);
        assert!((top_of(TextAlign::Center) - (1000.0 - used) / 2.0).abs() < 0.5);
        assert!((top_of(TextAlign::Right) - (1000.0 - used)).abs() < 0.5);
        // Cells stay in reading order and keep tiling under the shift.
        let content = aligned_content(text, 1000.0, TextAlign::Right);
        let layout = layout_content(&content, 1000.0);
        for pair in layout.cells.windows(2) {
            assert!(pair[1].top >= pair[0].top);
        }
    }

    #[test]
    fn text_align_auto_width_stays_top() {
        // Auto-width columns are snug: alignment must not shift them.
        let content = content_of(
            vec![vertical_paragraph(
                vec![make_span("あいうえお")],
                TextAlign::Right,
                1.0,
            )],
            60.0,
            GrowType::AutoWidth,
        );

        let layout = layout_content(&content, wrap_height(&content, 60.0));
        assert!(layout.cells[0].top.abs() < 0.01);
    }

    #[test]
    fn justify_fills_non_last_columns() {
        use std::collections::BTreeMap;
        let text = "あいうえお";
        // Uniform per-cell extent for the test font.
        let e = {
            let content = aligned_content(text, 1000.0, TextAlign::Left);
            layout_content(&content, 1000.0).cells[0].extent
        };
        // A 2.5-cell budget wraps into three columns: {0,1}, {2,3}, {4}. The
        // first two break with 0.5e of slack; the last column is one cell.
        let budget = e * 2.5;
        let content = aligned_content(text, budget, TextAlign::Justify);
        let layout = layout_content(&content, budget);

        let mut by_col: BTreeMap<usize, Vec<&VerticalCell>> = BTreeMap::new();
        for c in &layout.cells {
            by_col.entry(c.column).or_default().push(c);
        }
        assert!(by_col.len() >= 2, "text must wrap into multiple columns");
        let last = *by_col.keys().max().unwrap();

        for (&col, col_cells) in &by_col {
            if col != last && col_cells.len() > 1 {
                assert!(col_cells[0].top.abs() < 0.01, "col {col} top cell at top");
                let bottom = col_cells.last().unwrap().top + col_cells.last().unwrap().extent;
                assert!(
                    (bottom - budget).abs() < 0.5,
                    "justified col {col} fills the budget, bottom {bottom} vs {budget}"
                );
            }
        }
        // The last column keeps its natural top (start-aligned, not stretched).
        assert!(
            by_col[&last][0].top.abs() < 0.01,
            "last column is not justified"
        );
    }
}
