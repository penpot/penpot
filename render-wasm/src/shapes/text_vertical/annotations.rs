// Ruby (furigana) and emphasis marks (圏点) in vertical flow. Annotations
// stay out of `cells`, so base metrics, caret geometry and position data
// ignore them; they are placed from the base cells' final columns and flow
// extents.

use std::collections::HashMap;

use skia_safe::{self as skia, Font};

use crate::shapes::text_japanese::{
    distribute_ruby_pieces, emphasis_char_allowed, long_ruby_gap, ruby_overhang_room,
    ruby_overhangs, ruby_pieces, EMPHASIS_FONT_SCALE,
};
use crate::shapes::{
    AppliedTextTransform, FontFeatures, Paragraph, RubyOverhang, RubySide, TextContent, TextSpan,
};

use super::cells::Fonts;
use super::flow::FlowCell;
use super::layout::{
    column_base_center, CellKind, ParagraphTextMap, VerticalCell, VerticalColumn, VerticalLayout,
};
use super::orientation::segment_by_orientation;
use super::shaping::{shape_segment, shape_segment_with_fallbacks, span_font_families, ShapedRun};

/// One shaped ruby glyph, retaining its fallback-font run and source range.
#[derive(Debug, Clone, Copy)]
pub struct RubyGlyph {
    pub run: usize,
    pub glyph: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
    /// Part of a Western word set on its side, as base text in mixed
    /// orientation; other glyphs stand upright.
    pub sideways: bool,
    /// Flow extent: the ruby em upright, the glyph advance sideways.
    pub extent: f32,
}

/// The reading of one ruby span, beside its base in one column, painted from
/// `ruby_runs`. Group ruby keeps a base in one column.
#[derive(Debug, Clone)]
pub struct RubyCell {
    /// Glyphs in order; each keeps its shaped run so fallback fonts survive.
    pub glyphs: Vec<RubyGlyph>,
    pub paragraph: usize,
    pub span: usize,
    pub column: usize,
    /// Flow-axis top of each glyph.
    pub glyph_tops: Vec<f32>,
    pub font_size: f32,
    pub base_font_size: f32,
    pub side: RubySide,
    pub paint: usize,
}

/// One emphasis mark (圏点 / bouten) beside a base character: the
/// single-glyph `run`, centred on the character along the flow, in the
/// column's right-side gutter.
pub struct EmphasisMark {
    pub run: usize,
    /// Index of the annotated base cell in `VerticalLayout::cells`.
    pub cell: usize,
    /// Flow offset of the mark centre from the cell top.
    pub flow_center: f32,
    pub font_size: f32,
    /// Cross-axis offset past a stacked ruby layer.
    pub outside_offset: f32,
}

/// Cross-axis start of a vertical ruby strip. Ruby attaches to the edge of the
/// base em, centred in the column advance (`base_width`), so line height adds
/// no gap between ruby and base.
pub(super) fn ruby_strip_x(
    column: &VerticalColumn,
    ruby_font_size: f32,
    base_font_size: f32,
    side: RubySide,
) -> f32 {
    let base_center = column_base_center(column);
    match side {
        RubySide::Over => base_center + base_font_size / 2.0,
        RubySide::Under => base_center - base_font_size / 2.0 - ruby_font_size,
    }
}

/// The base text of one ruby span: the transformed UTF-16 range it covers in
/// its paragraph and the glyph count of its reading.
#[derive(Debug, Clone, Copy)]
pub(super) struct RubyBaseUnit {
    span: usize,
    start: usize,
    end: usize,
    /// Flow length of the reading set solid.
    ruby_length: f32,
    ruby_font_size: f32,
    overhang: RubyOverhang,
    /// Overhang room over the characters before and after the base.
    room: (f32, f32),
}

impl RubyBaseUnit {
    fn annotates(&self, cell: &VerticalCell) -> bool {
        cell.span == self.span && cell.start < self.end && cell.end > self.start
    }

    /// Flow length of the reading set solid.
    fn ruby_line(&self) -> f32 {
        self.ruby_length
    }
}

/// Ruby base units of a paragraph, from its spans' layout text. Shapes each
/// reading to count its glyphs.
pub(super) fn ruby_base_units(
    paragraph: &Paragraph,
    transforms: &[AppliedTextTransform],
    span_starts: &[usize],
    fonts: &Fonts,
) -> Vec<RubyBaseUnit> {
    paragraph
        .children()
        .iter()
        .zip(transforms)
        .enumerate()
        .filter(|(_, (span, transform))| span.has_ruby() && !transform.text.is_empty())
        .map(|(span_index, (span, transform))| RubyBaseUnit {
            span: span_index,
            start: span_starts[span_index],
            end: span_starts[span_index] + transform.text.encode_utf16().count(),
            ruby_length: ruby_glyphs(
                span.ruby_text(),
                0,
                &shape_ruby(span, fonts),
                0,
                span.ruby_font_size(),
            )
            .iter()
            .map(|glyph| glyph.extent)
            .sum(),
            ruby_font_size: span.ruby_font_size(),
            overhang: span.ruby_overhang,
            room: (0.0, 0.0),
        })
        .collect()
}

/// The span's reading shaped at the ruby size: CJK upright, Western words on
/// their side under mixed orientation, as base text is set. Each run comes
/// with whether it is sideways; clusters index the whole reading.
fn shape_ruby(span: &TextSpan, fonts: &Fonts) -> Vec<(ShapedRun, bool)> {
    let families = span_font_families(span, fonts.fallback_families);
    let mut utf8_start = 0usize;
    let mut runs = Vec::new();
    for segment in segment_by_orientation(span.ruby_text(), span.text_orientation) {
        let shaped = shape_segment_with_fallbacks(
            &segment.text,
            span.ruby_font_size(),
            &families,
            fonts.provider,
            segment.upright,
            span.font_features,
            fonts.fallback_mgr,
        );
        for mut run in shaped {
            for cluster in &mut run.clusters {
                *cluster += utf8_start as u32;
            }
            run.utf8_range = run.utf8_range.start + utf8_start..run.utf8_range.end + utf8_start;
            runs.push((run, !segment.upright));
        }
        utf8_start += segment.text.len();
    }
    runs
}

/// Overhang room of every unit over its flow neighbours, before placement.
/// Placement later removes the room at a column edge. A kana between two
/// readings lends the later one only what the earlier one leaves (JLREQ
/// Fig 138), so the two readings never meet over it.
pub(super) fn set_ruby_overhang_rooms(flow: &[FlowCell], ruby_units: &mut [RubyBaseUnit]) {
    let unit_of_span = units_by_span(ruby_units);
    let annotated = |index: usize| {
        let cell = &flow[index].cell;
        unit_of_span
            .get(&cell.span)
            .is_some_and(|unit| ruby_units[*unit].annotates(cell))
    };
    let indices = annotated_indices(flow.iter().map(|item| &item.cell), ruby_units);
    let mut claimed: HashMap<usize, f32> = HashMap::new();
    let rooms: Vec<(f32, f32)> = ruby_units
        .iter()
        .zip(&indices)
        .map(|(unit, indices)| {
            let (Some(first), Some(last)) = (indices.first(), indices.last()) else {
                return unit.room;
            };
            let room = |index: Option<usize>| {
                index.map_or(0.0, |index| {
                    ruby_overhang_room(
                        unit.overhang,
                        flow[index].ch,
                        annotated(index),
                        flow[index].cell.extent,
                        unit.ruby_font_size,
                    )
                })
            };
            let before = first.checked_sub(1);
            let after = Some(last + 1).filter(|index| *index < flow.len());
            let taken = before.and_then(|index| claimed.get(&index)).copied();
            let room = ((room(before) - taken.unwrap_or(0.0)).max(0.0), room(after));
            if let Some(after) = after {
                let base: f32 = indices.iter().map(|index| flow[*index].cell.extent).sum();
                claimed.insert(after, ruby_overhangs(unit.ruby_line() - base, room).1);
            }
            room
        })
        .collect();
    for (unit, room) in ruby_units.iter_mut().zip(rooms) {
        unit.room = room;
    }
}

/// Index of the unit of each ruby span.
fn units_by_span(ruby_units: &[RubyBaseUnit]) -> HashMap<usize, usize> {
    ruby_units
        .iter()
        .enumerate()
        .map(|(index, unit)| (unit.span, index))
        .collect()
}

/// Indices of the cells each unit annotates, in cell order.
fn annotated_indices<'a>(
    cells: impl Iterator<Item = &'a VerticalCell>,
    ruby_units: &[RubyBaseUnit],
) -> Vec<Vec<usize>> {
    let unit_of_span = units_by_span(ruby_units);
    let mut indices = vec![Vec::new(); ruby_units.len()];
    for (index, cell) in cells.enumerate() {
        if let Some(unit) = unit_of_span.get(&cell.span) {
            if ruby_units[*unit].annotates(cell) {
                indices[*unit].push(index);
            }
        }
    }
    indices
}

/// Indices of the cells of each (paragraph, span), in cell order.
fn cells_by_span(cells: &[VerticalCell]) -> HashMap<(usize, usize), Vec<usize>> {
    let mut by_span: HashMap<(usize, usize), Vec<usize>> = HashMap::new();
    for (index, cell) in cells.iter().enumerate() {
        by_span
            .entry((cell.paragraph, cell.span))
            .or_default()
            .push(index);
    }
    by_span
}

/// Indices of the cells of each column, in cell order.
fn cells_by_column(cells: &[VerticalCell]) -> HashMap<usize, Vec<usize>> {
    let mut by_column: HashMap<usize, Vec<usize>> = HashMap::new();
    for (index, cell) in cells.iter().enumerate() {
        by_column.entry(cell.column).or_default().push(index);
    }
    by_column
}

/// Overhang room (before, after) of each ruby span, keyed by (paragraph,
/// span).
pub(super) type RubyRooms = HashMap<(usize, usize), (f32, f32)>;

/// Overhang room of every ruby span of a paragraph, for `layout_ruby`.
pub(super) fn ruby_rooms(paragraph: usize, ruby_units: &[RubyBaseUnit]) -> RubyRooms {
    ruby_units
        .iter()
        .map(|unit| ((paragraph, unit.span), unit.room))
        .collect()
}

/// Grow the flow extent of base cells under long ruby before column planning,
/// so wrapping makes room (forced spreading). Only the part of the reading
/// that its overhang room cannot take grows the base: each cell grows by one
/// `long_ruby_gap`, its glyph centred, which leaves a full gap between
/// characters and half a gap at each end.
pub(super) fn grow_ruby_bases(flow: &mut [FlowCell], ruby_units: &[RubyBaseUnit]) {
    let all_indices = annotated_indices(flow.iter().map(|item| &item.cell), ruby_units);
    for (unit, indices) in ruby_units.iter().zip(&all_indices) {
        let base_total: f32 = indices.iter().map(|index| flow[*index].cell.extent).sum();
        let gap = long_ruby_gap(unit.ruby_length, base_total, unit.room, indices.len());
        if gap <= 0.0 {
            continue;
        }
        for index in indices {
            let cell = &mut flow[*index].cell;
            cell.extent += gap;
            cell.glyph_flow_shift += gap / 2.0;
            cell.ink_top += gap / 2.0;
            cell.ink_bottom += gap / 2.0;
        }
    }
}

/// Widen gaps between placed base cells under long ruby. Shifts only later
/// cells of the span, and only into slack before the next cell or the
/// column bottom, so columns never re-wrap. A base sits in one column.
pub(super) fn spread_ruby_base_cells(
    cells: &mut [VerticalCell],
    ruby_units: &[RubyBaseUnit],
    max_height: f32,
) {
    let column_limit = if max_height.is_finite() && max_height < f32::MAX {
        max_height
    } else {
        f32::MAX
    };
    let column_cells = cells_by_column(cells);
    let all_indices = annotated_indices(cells.iter(), ruby_units);
    for (unit, mut base) in ruby_units.iter().zip(all_indices) {
        if base.len() < 2 {
            continue;
        }
        base.sort_by(|a, b| cells[*a].top.total_cmp(&cells[*b].top));
        let ruby_line = unit.ruby_line() - unit.room.0 - unit.room.1;
        let first = base[0];
        let last = base[base.len() - 1];
        let base_top = cells[first].top;
        let base_bottom = cells[last].top + cells[last].extent;
        let base_extent = base_bottom - base_top;
        if ruby_line <= base_extent {
            continue;
        }

        let next_top = column_cells[&cells[first].column]
            .iter()
            .map(|index| &cells[*index])
            .filter(|cell| !unit.annotates(cell) && cell.top >= base_bottom)
            .map(|cell| cell.top)
            .min_by(f32::total_cmp);
        let limit = next_top.unwrap_or(column_limit);
        let spread = (ruby_line - base_extent).min((limit - base_bottom).max(0.0));
        if spread <= 0.0 {
            continue;
        }

        let gap = spread / (base.len() - 1) as f32;
        for (position, index) in base.iter().enumerate().skip(1) {
            cells[*index].top += gap * position as f32;
        }
    }
}

/// One `RubyGlyph` per shaped glyph of `ruby_text`, with UTF-16 offsets into
/// the span's untrimmed ruby string (`leading_utf16` is the trimmed prefix).
/// `run_start` is the index the first shaped run will take in `ruby_runs`.
fn ruby_glyphs(
    ruby_text: &str,
    leading_utf16: usize,
    shaped: &[(ShapedRun, bool)],
    run_start: usize,
    ruby_font_size: f32,
) -> Vec<RubyGlyph> {
    let mut glyphs: Vec<RubyGlyph> = shaped
        .iter()
        .enumerate()
        .flat_map(|(run_index, (run, sideways))| {
            run.clusters
                .iter()
                .enumerate()
                .map(move |(glyph, cluster)| {
                    let utf8 = (*cluster as usize).min(ruby_text.len());
                    RubyGlyph {
                        run: run_start + run_index,
                        glyph,
                        utf16_start: leading_utf16 + ruby_text[..utf8].encode_utf16().count(),
                        utf16_end: 0,
                        sideways: *sideways,
                        extent: if *sideways {
                            run.advances.get(glyph).copied().unwrap_or(0.0)
                        } else {
                            ruby_font_size
                        },
                    }
                })
        })
        .collect();
    let ruby_end = leading_utf16 + ruby_text.encode_utf16().count();
    for index in 0..glyphs.len() {
        glyphs[index].utf16_end = glyphs
            .get(index + 1)
            .map_or(ruby_end, |next| next.utf16_start);
    }
    glyphs
}

/// Overhang room of a base segment, without the sides that touch a column
/// edge: ruby never sticks out past the first or last character.
/// `column_cells` indexes the cells of the column.
fn room_inside_column(
    cells: &[VerticalCell],
    column_cells: &[usize],
    base_top: f32,
    base_bottom: f32,
    room: (f32, f32),
) -> (f32, f32) {
    let in_column = || column_cells.iter().map(|index| &cells[*index]);
    let has_before = in_column().any(|cell| cell.top + cell.extent <= base_top + 0.01);
    let has_after = in_column().any(|cell| cell.top >= base_bottom - 0.01);
    (
        if has_before { room.0 } else { 0.0 },
        if has_after { room.1 } else { 0.0 },
    )
}

/// Ruby (furigana) placement. Runs after column placement, since ruby follows
/// its base's final column and flow extent. Group ruby keeps each base in one
/// column, so each reading is one `RubyCell`. `rooms` holds the overhang
/// room of each (paragraph, span).
pub(super) fn layout_ruby(
    text_content: &TextContent,
    cells: &[VerticalCell],
    fonts: &Fonts,
    rooms: &RubyRooms,
) -> (Vec<ShapedRun>, Vec<RubyCell>) {
    let mut ruby_runs: Vec<ShapedRun> = Vec::new();
    let mut ruby_cells: Vec<RubyCell> = Vec::new();
    let span_cells = cells_by_span(cells);
    let column_cells = cells_by_column(cells);
    for (paragraph_index, paragraph) in text_content.paragraphs().iter().enumerate() {
        for (span_index, span) in paragraph.children().iter().enumerate() {
            if !span.has_ruby() {
                continue;
            }
            let Some(base) = span_cells.get(&(paragraph_index, span_index)) else {
                continue;
            };
            let (Some(first), Some(last)) = (
                base.iter()
                    .map(|index| &cells[*index])
                    .min_by(|a, b| a.top.total_cmp(&b.top)),
                base.iter()
                    .map(|index| &cells[*index])
                    .max_by(|a, b| (a.top + a.extent).total_cmp(&(b.top + b.extent))),
            ) else {
                continue;
            };
            let ruby_text = span.ruby_text();
            let ruby_font_size = span.ruby_font_size();
            let shaped = shape_ruby(span, fonts);
            let leading_utf16 = span.ruby[..span.ruby.len() - span.ruby.trim_start().len()]
                .encode_utf16()
                .count();
            let glyphs = ruby_glyphs(
                ruby_text,
                leading_utf16,
                &shaped,
                ruby_runs.len(),
                ruby_font_size,
            );
            if glyphs.is_empty() {
                continue;
            }
            ruby_runs.extend(shaped.into_iter().map(|(run, _)| run));

            let top = first.top;
            let bottom = last.top + last.extent;
            let room = rooms
                .get(&(paragraph_index, span_index))
                .copied()
                .unwrap_or((0.0, 0.0));
            let room = room_inside_column(cells, &column_cells[&first.column], top, bottom, room);
            let (pieces, placement) = ruby_pieces(
                &glyphs
                    .iter()
                    .map(|glyph| (glyph.sideways, glyph.extent))
                    .collect::<Vec<_>>(),
                ruby_font_size,
            );
            let piece_tops = distribute_ruby_pieces(
                top,
                (bottom - top).max(0.0),
                &pieces,
                span.ruby_align,
                room,
            );
            let glyph_tops = placement
                .iter()
                .map(|(piece, offset)| piece_tops[*piece] + offset)
                .collect();
            ruby_cells.push(RubyCell {
                glyphs,
                paragraph: paragraph_index,
                span: span_index,
                column: first.column,
                glyph_tops,
                font_size: ruby_font_size,
                base_font_size: span.font_size,
                side: span.ruby_side,
                paint: first.paint,
            });
        }
    }
    (ruby_runs, ruby_cells)
}

/// Flow centres (from the cell top) of the emphasis marks of one base cell:
/// one per eligible character of a sideways run, one for a tate-chu-yoko
/// composite and one centred on the glyph ink of an upright character.
/// `text` is the cell's slice of its span's transformed text.
fn emphasis_flow_centers(cell: &VerticalCell, text: &str, runs: &[ShapedRun]) -> Vec<f32> {
    let ink_center = || {
        if cell.ink_bottom > cell.ink_top {
            (cell.ink_top + cell.ink_bottom) / 2.0
        } else {
            cell.extent / 2.0
        }
    };
    match cell.kind {
        CellKind::Upright { .. }
        | CellKind::SyntheticRotated { .. }
        | CellKind::TateChuYoko { .. } => {
            if text.chars().any(emphasis_char_allowed) {
                vec![ink_center()]
            } else {
                Vec::new()
            }
        }
        CellKind::Rotated { run } => {
            let chars: Vec<char> = text.chars().collect();
            let Some(offsets) = runs
                .get(run)
                .and_then(|run| run.scalar_flow_offsets(cell.extent, chars.len()))
            else {
                return Vec::new();
            };
            chars
                .iter()
                .enumerate()
                .filter(|(_, ch)| emphasis_char_allowed(**ch))
                .map(|(index, _)| (offsets[index] + offsets[index + 1]) / 2.0)
                .collect()
        }
        _ => Vec::new(),
    }
}

/// (UTF-16, byte) offset of every character boundary of a text, end
/// included, for slicing it by UTF-16 ranges.
struct Utf16Boundaries<'a> {
    text: &'a str,
    boundaries: Vec<(usize, usize)>,
}

impl<'a> Utf16Boundaries<'a> {
    fn new(text: &'a str) -> Self {
        let mut utf16 = 0;
        let mut boundaries: Vec<(usize, usize)> = text
            .char_indices()
            .map(|(byte, ch)| {
                let boundary = (utf16, byte);
                utf16 += ch.len_utf16();
                boundary
            })
            .collect();
        boundaries.push((utf16, text.len()));
        Self { text, boundaries }
    }

    /// The characters that start inside the UTF-16 range `start..end`.
    fn slice(&self, start: usize, end: usize) -> &'a str {
        let byte = |offset: usize| {
            let index = self
                .boundaries
                .partition_point(|(utf16, _)| *utf16 < offset);
            self.boundaries[index.min(self.boundaries.len() - 1)].1
        };
        let start = byte(start);
        &self.text[start..byte(end).max(start)]
    }
}

/// Emphasis marks (圏点 / bouten) of each span with `text_emphasis`, shaped
/// once per span: one per upright character, per letter of a sideways run
/// and per tate-chu-yoko composite. Whitespace and Japanese punctuation get
/// no mark, as in CSS `text-emphasis`.
pub(super) fn layout_emphasis(
    text_content: &TextContent,
    cells: &[VerticalCell],
    runs: &[ShapedRun],
    text_maps: &[ParagraphTextMap],
    fonts: &Fonts,
) -> (Vec<ShapedRun>, Vec<EmphasisMark>) {
    let mut emphasis_runs: Vec<ShapedRun> = Vec::new();
    let mut emphasis_marks: Vec<EmphasisMark> = Vec::new();
    let span_cells = cells_by_span(cells);
    for (paragraph_index, paragraph) in text_content.paragraphs().iter().enumerate() {
        for (span_index, span) in paragraph.children().iter().enumerate() {
            let Some(mark) = span.text_emphasis.mark_char() else {
                continue;
            };
            let Some(span_cells) = span_cells.get(&(paragraph_index, span_index)) else {
                continue;
            };
            let mark_font_size = span.font_size * EMPHASIS_FONT_SCALE;
            let families = span_font_families(span, fonts.fallback_families);
            let match_family = |family: &str| {
                fonts
                    .provider
                    .match_family_style(family, skia::FontStyle::default())
            };
            let typeface = families
                .iter()
                .filter_map(|family| match_family(family))
                .find(|typeface| typeface.unichar_to_glyph(mark as i32) != 0)
                .or_else(|| match_family(&families[0]));
            let Some(typeface) = typeface else {
                continue;
            };
            let mut shaped = shape_segment(
                &mark.to_string(),
                &Font::new(typeface, mark_font_size),
                true,
                FontFeatures::None,
                fonts.fallback_mgr.clone(),
            );
            if shaped.first().is_none_or(|run| run.glyphs.is_empty()) {
                continue;
            }
            let run_index = emphasis_runs.len();
            emphasis_runs.push(shaped.remove(0));
            let text_map = &text_maps[paragraph_index];
            let span_start = text_map.span_starts[span_index];
            // Cell offsets index the span's transformed text.
            let span_text = Utf16Boundaries::new(&text_map.span_transforms[span_index].text);
            for cell_index in span_cells.iter().copied() {
                let cell = &cells[cell_index];
                let text = span_text.slice(cell.start - span_start, cell.end - span_start);
                for flow_center in emphasis_flow_centers(cell, text, runs) {
                    emphasis_marks.push(EmphasisMark {
                        run: run_index,
                        cell: cell_index,
                        flow_center,
                        font_size: mark_font_size,
                        outside_offset: span.emphasis_ruby_offset(),
                    });
                }
            }
        }
    }
    (emphasis_runs, emphasis_marks)
}

/// Centre of an emphasis mark, relative to the layout's content origin: in
/// its column's gutter past any stacked ruby, at its character's centre.
pub(super) fn emphasis_mark_center(layout: &VerticalLayout, mark: &EmphasisMark) -> (f32, f32) {
    let cell = &layout.cells[mark.cell];
    let base_font_size = mark.font_size / EMPHASIS_FONT_SCALE;
    let x = column_base_center(&layout.columns[cell.column])
        + base_font_size / 2.0
        + mark.outside_offset
        + mark.font_size / 2.0;
    (x, cell.top + mark.flow_center)
}

#[cfg(test)]
mod tests {
    use super::super::layout::VerticalLayout;
    use super::super::positions::{position_data, DIRECTION_VERTICAL_RUBY};
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::{AnnotationClearance, GrowType, RubySize};
    use crate::shapes::{
        TextAlign, TextEmphasis, TextOrientation, TextSpan, TextTransform, VerticalAlign,
    };

    fn emphasis_content(base: &str, emphasis: TextEmphasis) -> TextContent {
        spans_content(
            vec![TextSpan {
                text_emphasis: emphasis,
                text_orientation: TextOrientation::Upright,
                ..make_span(base)
            }],
            400.0,
        )
    }

    fn ruby_content_with_line_height(line_height: f32) -> TextContent {
        let span = TextSpan {
            ruby: "ab".to_string(),
            ..make_span("AB")
        };
        content_of(
            vec![vertical_paragraph(vec![span], TextAlign::Left, line_height)],
            400.0,
            GrowType::Fixed,
        )
    }

    #[test]
    fn utf16_slices_keep_the_characters_starting_in_range() {
        let linear = |text: &str, start: usize, end: usize| {
            let mut offset = 0;
            text.chars()
                .filter(|ch| {
                    let inside = offset >= start && offset < end;
                    offset += ch.len_utf16();
                    inside
                })
                .collect::<String>()
        };
        let text = "a𠮷漢🇯🇵b";
        let boundaries = Utf16Boundaries::new(text);
        for start in 0..10 {
            for end in 0..10 {
                assert_eq!(
                    boundaries.slice(start, end),
                    linear(text, start, end),
                    "{start}..{end}"
                );
            }
        }
    }

    #[test]
    fn emphasis_span_reserves_gutter_and_emits_one_mark_per_upright_cell() {
        let mut content = emphasis_content("AB", TextEmphasis::FilledDot);
        content.paragraphs_mut()[0].children_mut()[0].annotation_clearance =
            AnnotationClearance::Auto;
        let layout = layout_content(&content, 400.0);
        let upright = layout
            .cells
            .iter()
            .filter(|c| matches!(c.kind, CellKind::Upright { .. }))
            .count();
        assert_eq!(upright, 2, "AB upright yields two base cells");
        assert_eq!(
            layout.emphasis_marks.len(),
            upright,
            "one emphasis mark per upright base cell"
        );
        for column in &layout.columns {
            assert!(
                column.width > column.base_width,
                "an emphasis paragraph reserves a gutter beside the base band"
            );
        }
        let mark = &layout.emphasis_marks[0];
        assert!(
            !layout.emphasis_runs[mark.run].glyphs.is_empty(),
            "the emphasis mark must be shaped"
        );
    }

    #[test]
    fn emphasis_skips_whitespace_cells() {
        let content = emphasis_content("A B", TextEmphasis::FilledDot);
        let layout = layout_content(&content, 400.0);
        let upright = layout
            .cells
            .iter()
            .filter(|c| matches!(c.kind, CellKind::Upright { .. }))
            .count();
        assert_eq!(upright, 3, "'A B' upright yields three base cells");
        assert_eq!(
            layout.emphasis_marks.len(),
            2,
            "the whitespace cell gets no emphasis mark"
        );
    }

    #[test]
    fn emphasis_skips_japanese_commas_stops_and_brackets() {
        let content = emphasis_content("A、。（B）」", TextEmphasis::FilledDot);
        let layout = layout_content(&content, 400.0);

        assert_eq!(
            layout.emphasis_marks.len(),
            2,
            "only A and B receive emphasis marks"
        );
    }

    #[test]
    fn emphasis_follows_the_transformed_text() {
        // Uppercase expands ß into SS, so the transformed cells S S 、 A are
        // offset from the source text ß、A.
        let content = spans_content(
            vec![TextSpan {
                text_emphasis: TextEmphasis::FilledDot,
                text_orientation: TextOrientation::Upright,
                text_transform: Some(TextTransform::Uppercase),
                ..make_span("ß、A")
            }],
            400.0,
        );
        let layout = layout_content(&content, 400.0);
        assert_eq!(layout.cells.len(), 4);
        let marked: Vec<usize> = layout.emphasis_marks.iter().map(|mark| mark.cell).collect();
        assert_eq!(marked, vec![0, 1, 3], "S, S and A are marked; 、 is not");
    }

    #[test]
    fn no_emphasis_emits_no_marks() {
        let content = make_content(&["AB"], 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout.emphasis_marks.is_empty());
        assert!(layout.emphasis_runs.is_empty());
    }

    #[test]
    fn long_ruby_stays_inside_the_column_end_under_end_alignment() {
        // JLREQ Fig 141: ……の径(こみち) set at the column end; the reading
        // overhangs の but never the line end.
        let height = 8.0 * EM;
        let spans = vec![
            make_span("……の"),
            TextSpan {
                ruby: "こみち".to_string(),
                ..make_span("径")
            },
        ];
        let content = content_of(
            vec![vertical_paragraph(spans, TextAlign::Right, 1.0)],
            height,
            GrowType::Fixed,
        );
        let layout = layout_with_height(&provider(VMTX_TEST_FONT), &content, height);
        let ruby = &layout.ruby_cells[0];
        let end = ruby
            .glyph_tops
            .iter()
            .zip(&ruby.glyphs)
            .map(|(top, glyph)| top + glyph.extent)
            .fold(f32::MIN, f32::max);
        let base = layout.cells.last().expect("base cell");
        assert!(
            (base.top + base.extent - height).abs() < 0.01,
            "the base sits at the column end"
        );
        assert!(end <= height + 0.01, "ruby ends at {end}, past {height}");
    }

    #[test]
    fn long_ruby_overhangs_the_kana_side_only() {
        // JLREQ Fig 122: の旬(しゅん)又 hangs しゅん over の, never over 又.
        let content = spans_content(
            vec![
                make_span("の"),
                TextSpan {
                    ruby: "しゅん".to_string(),
                    ..make_span("旬")
                },
                make_span("又"),
            ],
            400.0,
        );
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let ruby = &layout.ruby_cells[0];
        let base = layout
            .cells
            .iter()
            .find(|cell| cell.span == 1)
            .expect("base");
        let start = ruby.glyph_tops[0];
        let end = ruby.glyph_tops[2] + ruby.glyphs[2].extent;
        assert!(
            (end - (base.top + base.extent)).abs() < 0.01,
            "ends with 旬: {end}"
        );
        assert!(
            (base.top - start - EM / 2.0).abs() < 0.01,
            "hangs 1/2 em over の"
        );
    }

    #[test]
    fn neighbouring_readings_never_meet_over_a_shared_kana() {
        // JLREQ Fig 138: に暁(あかつき)の趣(おもむき)を. あかつき hangs over の;
        // おもむき may not, so 趣 spreads and the readings stay apart.
        let content = spans_content(
            vec![
                make_span("に"),
                TextSpan {
                    ruby: "あかつき".to_string(),
                    ..make_span("暁")
                },
                make_span("の"),
                TextSpan {
                    ruby: "おもむき".to_string(),
                    ..make_span("趣")
                },
                make_span("を"),
            ],
            400.0,
        );
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        let no = layout.cells.iter().find(|cell| cell.span == 2).expect("の");
        let (first, second) = (&layout.ruby_cells[0], &layout.ruby_cells[1]);
        let first_end = first.glyph_tops[3] + first.glyphs[3].extent;
        let second_start = second.glyph_tops[0];
        assert!(first_end > no.top + 0.01, "あかつき hangs over の");
        assert!(
            second_start >= no.top + no.extent - 0.01,
            "おもむき does not"
        );
        assert!(
            second_start - first_end >= EM / 2.0 - 0.01,
            "a ruby em apart"
        );
    }

    #[test]
    fn western_ruby_is_one_sideways_word_centred_on_its_base() {
        // JLREQ Fig 112: "editor" beside 編集者 turns on its side like base
        // Latin text and is never spread letter by letter.
        let content = ruby_content("くくくくく", "editor", 400.0);
        let layout = layout_content(&content, 400.0);
        let ruby = &layout.ruby_cells[0];
        assert_eq!(ruby.glyphs.len(), 6);
        assert!(ruby.glyphs.iter().all(|glyph| glyph.sideways));
        for (index, pair) in ruby.glyph_tops.windows(2).enumerate() {
            assert!(
                (pair[1] - pair[0] - ruby.glyphs[index].extent).abs() < 0.01,
                "letters are set at their own advances"
            );
        }
        let word: f32 = ruby.glyphs.iter().map(|glyph| glyph.extent).sum();
        let base_bottom = layout
            .cells
            .last()
            .map_or(0.0, |cell| cell.top + cell.extent);
        let before = ruby.glyph_tops[0] - layout.cells[0].top;
        let after = base_bottom - (ruby.glyph_tops[0] + word);
        assert!(
            (before - after).abs() < 0.01,
            "centred: {before} vs {after}"
        );
    }

    #[test]
    fn ruby_span_reserves_gutter_and_emits_ruby_cells() {
        let mut content = ruby_content("AB", "ab", 400.0);
        content.paragraphs_mut()[0].children_mut()[0].annotation_clearance =
            AnnotationClearance::Auto;
        let layout = layout_content(&content, 400.0);
        assert!(
            !layout.ruby_cells.is_empty(),
            "a span with ruby must emit ruby cells"
        );
        for column in &layout.columns {
            assert!(
                column.width > column.base_width,
                "a ruby paragraph reserves a gutter beside the base band"
            );
        }
        let ruby = &layout.ruby_cells[0];
        assert!(
            ruby.glyphs.iter().all(|glyph| layout
                .ruby_runs
                .get(glyph.run)
                .and_then(|run| run.glyphs.get(glyph.glyph))
                .is_some()),
            "every ruby glyph must retain its shaped run"
        );
    }

    #[test]
    fn ruby_size_and_side_control_gutter_geometry() {
        let mut content = ruby_content("AB", "ab", 400.0);
        let span = &mut content.paragraphs_mut()[0].children_mut()[0];
        span.ruby_size = RubySize::Quarter;
        span.ruby_side = RubySide::Under;
        span.annotation_clearance = AnnotationClearance::Auto;

        let layout = layout_content(&content, 400.0);
        let column = &layout.columns[0];
        let ruby = &layout.ruby_cells[0];

        assert!((ruby.font_size - 5.0).abs() < 0.001);
        assert!((column.base_offset - 5.0).abs() < 0.001);
        assert!((column.width - column.base_width - 5.0).abs() < 0.001);
        assert_eq!(ruby.side, RubySide::Under);
    }

    #[test]
    fn auto_clearance_stacks_ruby_and_emphasis_gutters() {
        let mut none_content = ruby_content("漢字", "かんじ", 400.0);
        none_content.paragraphs_mut()[0].children_mut()[0].text_emphasis = TextEmphasis::FilledDot;
        let none = layout_content(&none_content, 400.0);

        let mut auto_content = none_content.clone();
        auto_content.paragraphs_mut()[0].children_mut()[0].annotation_clearance =
            AnnotationClearance::Auto;
        let auto = layout_content(&auto_content, 400.0);

        let none_gutter = none.columns[0].width - none.columns[0].base_width;
        let auto_gutter = auto.columns[0].width - auto.columns[0].base_width;
        assert!(none_gutter.abs() < 0.001, "none keeps the column advance");
        assert!((auto_gutter - 20.0).abs() < 0.001);
        assert!(auto.emphasis_marks[0].outside_offset > 0.0);
    }

    #[test]
    fn ruby_attachment_distance_is_independent_of_line_height() {
        let compact_content = ruby_content_with_line_height(1.0);
        let loose_content = ruby_content_with_line_height(2.0);
        let compact = layout_content(&compact_content, 400.0);
        let loose = layout_content(&loose_content, 400.0);

        let attachment_gap = |layout: &VerticalLayout| {
            let ruby = &layout.ruby_cells[0];
            let column = &layout.columns[ruby.column];
            let base_right = column_base_center(column) + ruby.base_font_size / 2.0;
            ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side) - base_right
        };

        assert!(
            loose.columns[0].base_width > compact.columns[0].base_width,
            "line height must still increase column progression"
        );
        assert!(attachment_gap(&compact).abs() < 0.001);
        assert!(attachment_gap(&loose).abs() < 0.001);

        let loose_positions = position_data(&loose, &loose_content.bounds(), VerticalAlign::Top);
        let ruby_position = loose_positions
            .iter()
            .find(|entry| entry.direction == DIRECTION_VERTICAL_RUBY)
            .expect("ruby position data");
        assert!(
            (ruby_position.width - loose.ruby_cells[0].font_size).abs() < 0.001,
            "export geometry must use the attached ruby strip, not the line-height band"
        );
    }

    #[test]
    fn vertical_ruby_preserves_every_fallback_run() {
        let content = ruby_content("AB", "aあ", 400.0);
        let provider = provider_with_fallback(TEST_FONT, VMTX_TEST_FONT);
        let layout = layout_with_fallback(&provider, &content, 400.0, &["fallback".to_string()]);

        let referenced_runs: std::collections::BTreeSet<usize> = layout
            .ruby_cells
            .iter()
            .flat_map(|cell| cell.glyphs.iter().map(|glyph| glyph.run))
            .collect();
        assert_eq!(referenced_runs.len(), 2, "ruby must retain both typefaces");
        assert_eq!(
            layout
                .ruby_cells
                .iter()
                .map(|cell| cell.glyphs.len())
                .sum::<usize>(),
            layout.ruby_runs.iter().map(|run| run.glyphs.len()).sum(),
            "every fallback glyph must be assigned to a ruby cell"
        );

        let position = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        let ruby_position = position
            .iter()
            .find(|entry| entry.direction == DIRECTION_VERTICAL_RUBY)
            .expect("ruby position data");
        assert_eq!(ruby_position.start_pos, 0);
        assert_eq!(ruby_position.end_pos, 2);
    }

    #[test]
    fn no_ruby_keeps_base_width_equal_to_width() {
        let content = make_content(&["AB"], 400.0);
        let layout = layout_content(&content, 400.0);
        assert!(layout.ruby_cells.is_empty());
        for column in &layout.columns {
            assert_eq!(
                column.width, column.base_width,
                "columns without ruby must not reserve a gutter"
            );
        }
    }

    #[test]
    fn ruby_side_attaches_to_opposite_base_edges() {
        let column = VerticalColumn {
            x: 10.0,
            width: 40.0,
            base_offset: 8.0,
            base_width: 24.0,
        };

        assert_eq!(ruby_strip_x(&column, 8.0, 20.0, RubySide::Over), 40.0);
        assert_eq!(ruby_strip_x(&column, 8.0, 20.0, RubySide::Under), 12.0);
    }

    #[test]
    fn long_ruby_spreads_a_vertical_base_with_half_gaps_at_the_ends() {
        let plain = layout_content(&make_content(&["日本"], 1000.0), 1000.0);
        let mut content = ruby_content("日本", "にほんごです", 1000.0);
        content.paragraphs_mut()[0].children_mut()[0].ruby_overhang = RubyOverhang::None;
        let layout = layout_content(&content, 1000.0);
        // Six half-em ruby glyphs; what the base lacks is shared by its two
        // characters.
        let base: f32 = plain.cells.iter().map(|cell| cell.extent).sum();
        let gap = (6.0 * EM / 2.0 - base) / 2.0;
        assert!(gap > 0.0);
        for (spread, solid) in layout.cells.iter().zip(&plain.cells) {
            assert!((spread.extent - (solid.extent + gap)).abs() < 0.01);
            assert!((spread.glyph_flow_shift - (solid.glyph_flow_shift + gap / 2.0)).abs() < 0.01);
        }
    }

    #[test]
    fn ruby_base_spreading_expands_gap_for_long_reading() {
        let content = ruby_content("日本", "にほんご", 120.0);
        let layout = layout_content(&content, 120.0);
        let plain = layout_content(&make_content(&["日本"], 120.0), 120.0);
        let ruby_cells: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell.span == 0 && cell.column == 0)
            .collect();
        assert_eq!(ruby_cells.len(), 2);
        let spacing = ruby_cells[1].top - ruby_cells[0].top;
        assert!(
            spacing > plain.cells[1].top - plain.cells[0].top,
            "long ruby should spread the base characters apart, got {}",
            spacing
        );
        let base_extent = ruby_cells[1].top + ruby_cells[1].extent - ruby_cells[0].top;
        assert!(
            base_extent >= 40.0,
            "base span should be at least as long as the 4-glyph half-em ruby line"
        );
    }

    #[test]
    fn ruby_base_spreading_does_not_overlap_following_text() {
        let content = make_content_with_spans(&["日本", "語"], 60.0);
        let mut content = content;
        content.paragraphs_mut()[0].children_mut()[0].ruby = "にほんご".to_string();
        let layout = layout_content(&content, 60.0);
        let mut same_column: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell.column == 0)
            .collect();
        same_column.sort_by(|a, b| {
            a.top
                .partial_cmp(&b.top)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        for pair in same_column.windows(2) {
            assert!(
                pair[0].top + pair[0].extent <= pair[1].top + 0.001,
                "base spreading must not overlap the following cell"
            );
        }
    }

    #[test]
    fn ruby_base_spreading_forces_room_when_no_slack() {
        // Base 日本 (40) with a 60-long reading, then 語, in a 60 column: with
        // no slack after placement, the base grows before planning and pushes
        // 語 to the next column.
        let mut content = make_content_with_spans(&["日本", "語"], 60.0);
        content.paragraphs_mut()[0].children_mut()[0].ruby = "にほんごです".to_string();
        let layout = layout_content(&content, 60.0);
        let base: Vec<&VerticalCell> = layout.cells.iter().filter(|c| c.span == 0).collect();
        assert_eq!(base.len(), 2);
        let base_extent =
            base.last().unwrap().top + base.last().unwrap().extent - base.first().unwrap().top;
        assert!(
            base_extent >= 60.0 - 0.001,
            "the base span must grow to the ruby line length, got {}",
            base_extent
        );
        let follower = layout
            .cells
            .iter()
            .find(|c| c.span == 1)
            .expect("follower cell");
        assert_ne!(
            follower.column, base[0].column,
            "the follower must wrap to the next column instead of overlapping"
        );
    }

    #[test]
    fn emphasis_marks_sideways_letters_and_tcy() {
        let content = spans_content(
            vec![
                TextSpan {
                    text_emphasis: TextEmphasis::FilledDot,
                    ..make_span("かabc")
                },
                TextSpan {
                    text_emphasis: TextEmphasis::FilledDot,
                    text_combine_upright: crate::shapes::TextCombineUpright::All,
                    ..make_span("12")
                },
            ],
            400.0,
        );
        let layout = layout_with(&provider_with_fallback(VMTX_TEST_FONT, TEST_FONT), &content);
        assert_eq!(
            layout.emphasis_marks.len(),
            5,
            "か, a, b, c and the TCY composite each get one mark"
        );
    }

    #[test]
    fn emphasis_mark_centres_on_the_glyph_not_the_spacing() {
        let content = spans_content(
            vec![TextSpan {
                text_emphasis: TextEmphasis::FilledDot,
                letter_spacing: 10.0,
                ..make_span("かき")
            }],
            400.0,
        );
        let layout = layout_with(&provider(VMTX_TEST_FONT), &content);
        for mark in &layout.emphasis_marks {
            let cell = &layout.cells[mark.cell];
            let ink_centre = cell.top + (cell.ink_top + cell.ink_bottom) / 2.0;
            let (_, centre) = emphasis_mark_center(&layout, mark);
            assert!(
                (centre - ink_centre).abs() < 0.01,
                "mark at {centre}, glyph ink centred at {ink_centre}"
            );
        }
    }

    fn neighbour_ruby_content(overhang: RubyOverhang) -> TextContent {
        spans_content(
            vec![
                make_span("か"),
                TextSpan {
                    ruby: "かんじかんじ".to_string(),
                    ruby_overhang: overhang,
                    ..make_span("漢")
                },
                TextSpan {
                    ruby: "じ".to_string(),
                    ..make_span("字")
                },
                make_span("か"),
            ],
            1000.0,
        )
    }

    fn ruby_of_span(layout: &VerticalLayout, span: usize) -> &RubyCell {
        layout
            .ruby_cells
            .iter()
            .find(|ruby| ruby.span == span)
            .expect("ruby cell")
    }

    #[test]
    fn long_reading_never_covers_the_next_ruby_base() {
        let layout = layout_with(
            &provider(VMTX_TEST_FONT),
            &neighbour_ruby_content(RubyOverhang::Auto),
        );
        let ruby = ruby_of_span(&layout, 1);
        let next_base = layout.cells.iter().find(|cell| cell.span == 2).unwrap();
        let ruby_bottom = ruby.glyph_tops.last().unwrap() + ruby.font_size;
        assert!(
            ruby_bottom <= next_base.top + 0.01,
            "reading ends at {ruby_bottom}, the next ruby base starts at {}",
            next_base.top
        );
    }

    #[test]
    fn long_reading_overhangs_kana_by_at_most_one_ruby_character() {
        let layout = layout_with(
            &provider(VMTX_TEST_FONT),
            &neighbour_ruby_content(RubyOverhang::Auto),
        );
        let ruby = ruby_of_span(&layout, 1);
        let base = layout.cells.iter().find(|cell| cell.span == 1).unwrap();
        assert!(ruby.glyph_tops[0] >= base.top - ruby.font_size - 0.01);
    }

    #[test]
    fn long_reading_without_overhang_grows_a_single_base() {
        let layout = layout_with(
            &provider(VMTX_TEST_FONT),
            &neighbour_ruby_content(RubyOverhang::None),
        );
        let ruby = ruby_of_span(&layout, 1);
        let base = layout.cells.iter().find(|cell| cell.span == 1).unwrap();
        assert!(ruby.glyph_tops[0] >= base.top - 0.01);
        assert!(ruby.glyph_tops.last().unwrap() + ruby.font_size <= base.top + base.extent + 0.01);
    }

    #[test]
    fn ruby_base_moves_to_the_next_column_whole() {
        let content = |height: f32| {
            spans_content(
                vec![
                    make_span("あい"),
                    TextSpan {
                        ruby: "にほん".to_string(),
                        ..make_span("日本")
                    },
                ],
                height,
            )
        };
        let wide = layout_content(&content(1000.0), 1000.0);
        // Room for あい and the first base character only.
        let height = wide.cells[..3].iter().map(|cell| cell.extent).sum::<f32>() + 0.5;
        let layout = layout_content(&content(height), height);
        let base_columns: Vec<usize> = layout
            .cells
            .iter()
            .filter(|cell| cell.span == 1)
            .map(|cell| cell.column)
            .collect();
        assert_eq!(base_columns, vec![1, 1], "the ruby base is one group");
        let ruby_columns: std::collections::BTreeSet<usize> =
            layout.ruby_cells.iter().map(|r| r.column).collect();
        assert_eq!(ruby_columns.into_iter().collect::<Vec<_>>(), vec![1]);
    }

    #[test]
    fn ruby_base_longer_than_a_column_keeps_its_reading_whole() {
        let content = ruby_content("日本語文", "にほんごぶん", 40.0);
        let layout = layout_content(&content, 40.0);

        let base_columns: std::collections::BTreeSet<usize> =
            layout.cells.iter().map(|c| c.column).collect();
        assert_eq!(base_columns.len(), 1, "the ruby base never wraps");
        assert_eq!(layout.ruby_cells.len(), 1, "the reading stays in one piece");
        let placed: usize = layout.ruby_cells.iter().map(|r| r.glyphs.len()).sum();
        assert_eq!(
            placed,
            layout.ruby_runs.iter().map(|run| run.glyphs.len()).sum()
        );
    }
}
