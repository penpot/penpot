use skia_safe::{
    self as skia,
    textlayout::{TextDecoration, TypefaceFontProvider},
    FontMgr,
};

use crate::globals::get_resources;
use crate::math::Rect;
use crate::shapes::japanese::JapaneseClass;
use crate::shapes::text_japanese::EMPHASIS_FONT_SCALE;
use crate::shapes::{
    merge_fills, AppliedTextTransform, GrowType, Paragraph, RubySide, TextAlign, TextContent,
    TextSpan, VerticalAlign,
};
use crate::utils::get_fallback_fonts;

use super::annotations::{
    grow_ruby_bases, layout_emphasis, layout_ruby, ruby_base_units, spread_ruby_base_cells,
    EmphasisMark, RubyCell,
};
use super::cells::{Fonts, SpanCells, WarichuNote};
use super::flow::{
    align_offset_along_column, apply_inter_script_spacing, apply_ordered_oikomi, flow_classes,
    is_bounded, materialize_explicit_pair_spacing, ordered_expansion_offsets,
    plan_with_edge_trimming, preferred_pair_spacing, shed_punctuation_aki, FlowCell, FIT_TOLERANCE,
};
use super::shaping::ShapedRun;

#[derive(Debug, Clone, Copy)]
pub enum CellKind {
    Upright {
        run: usize,
        glyph: usize,
        count: usize,
    },
    /// An upright-flow character whose font lacks a `vert`/`vrt2` alternate,
    /// rotated in its own cell so wrapping, kinsoku and editor offsets stay
    /// per character.
    SyntheticRotated {
        run: usize,
        glyph: usize,
        count: usize,
    },
    Rotated {
        run: usize,
    },
    TateChuYoko {
        /// Runs `[run_start, run_start + run_count)`, laid side by side.
        run_start: usize,
        run_count: usize,
        scale: f32,
    },
    /// One span's share of a warichu piece. Every cell of a piece has the
    /// piece's box; the `*_top`/`*_extent` pairs place this span's glyphs
    /// along each sub-line, relative to `top`.
    Warichu {
        /// First `first_count` runs are the right sub-line, the rest the left.
        run_start: usize,
        run_count: usize,
        first_count: usize,
        /// UTF-16 length of the first sub-line part (split point from `start`).
        first_chars: usize,
        first_top: f32,
        first_extent: f32,
        second_top: f32,
        second_extent: f32,
    },
}

/// One placed piece of the vertical flow. Offsets are UTF-16,
/// paragraph-relative (all spans concatenated), in original text space.
pub struct VerticalCell {
    pub kind: CellKind,
    pub paragraph: usize,
    pub span: usize,
    pub start: usize,
    pub end: usize,
    pub column: usize,
    pub top: f32,
    /// Flow-axis advance, from `vmtx` when present, else the horizontal one.
    pub extent: f32,
    /// Oikomi lower bound: the glyph frame before removable pair spacing.
    pub minimum_oikomi_extent: f32,
    /// Shaped horizontal advance, used to centre the glyph in the column.
    pub h_advance: f32,
    /// Visible glyph-ink edges along the flow axis, relative to `top`.
    pub ink_top: f32,
    pub ink_bottom: f32,
    pub paint: usize,
    /// Span font size, for decoration bar geometry.
    pub font_size: f32,
    /// Span text decoration, painted as vertical bars along the column.
    pub decoration: Option<TextDecoration>,
    /// Draw-only flow shift for half-width opening punctuation (jlreq aki).
    pub glyph_flow_shift: f32,
}

/// A laid-out column, x measured from the *content* left edge (the content
/// block is anchored to the shape's right edge by consumers).
#[derive(Debug, Clone, Copy)]
pub struct VerticalColumn {
    pub x: f32,
    pub width: f32,
    /// Reserved annotation gutter before the base band (left / `under`).
    pub base_offset: f32,
    /// Line-height column advance that base glyphs centre on.
    pub base_width: f32,
}

pub(super) fn column_base_center(column: &VerticalColumn) -> f32 {
    column.x + column.base_offset + column.base_width / 2.0
}

pub struct VerticalLayout {
    pub runs: Vec<ShapedRun>,
    pub paints: Vec<skia::Paint>,
    pub cells: Vec<VerticalCell>,
    pub columns: Vec<VerticalColumn>,
    /// Shaped ruby annotation runs, indexed by `RubyCell::run`.
    pub ruby_runs: Vec<ShapedRun>,
    pub ruby_cells: Vec<RubyCell>,
    /// Single-glyph emphasis runs, indexed by `EmphasisMark::run`.
    pub emphasis_runs: Vec<ShapedRun>,
    pub emphasis_marks: Vec<EmphasisMark>,
    /// Per paragraph: [start, end) range into `columns`.
    pub paragraph_columns: Vec<(usize, usize)>,
    /// Per paragraph: UTF-16 start offset of each span (paragraph-relative).
    pub span_utf16_starts: Vec<Vec<usize>>,
    /// Per paragraph: source UTF-16 start offset of each span.
    pub span_source_utf16_starts: Vec<Vec<usize>>,
    /// Per paragraph and span: transformed scalar ownership in source text.
    pub span_transforms: Vec<Vec<AppliedTextTransform>>,
    /// Per paragraph: UTF-16 offset of each scalar boundary, for editor positions.
    pub paragraph_utf16_boundaries: Vec<Vec<usize>>,
    pub width: f32,
    pub height: f32,
}

impl VerticalLayout {
    /// Content origin (top-left of the laid-out block) in the coordinate
    /// space of `bounds`.
    pub fn origin(&self, bounds: &Rect, align: VerticalAlign) -> (f32, f32) {
        (
            bounds.left + block_axis_offset(bounds.width(), self.width, align),
            bounds.top,
        )
    }
}

/// Horizontal offset of vertical content within its shape. `VerticalAlign`
/// top/center/bottom mean block start/center/end: right/center/left in
/// vertical-rl. Content wider than the shape keeps that anchor and overflows
/// toward the block end, so a start-anchored block grows leftward.
pub fn block_axis_offset(container_width: f32, content_width: f32, align: VerticalAlign) -> f32 {
    let slack = container_width - content_width;
    match align {
        VerticalAlign::Top => slack,
        VerticalAlign::Center => slack / 2.0,
        VerticalAlign::Bottom => 0.0,
    }
}

/// Column-wrap limit: auto-width shapes grow to fit (columns never wrap);
/// all others, auto-height included, wrap at the shape height.
pub fn wrap_height(text_content: &TextContent, height: f32) -> f32 {
    match text_content.grow_type() {
        GrowType::AutoWidth => f32::MAX,
        _ => f32::max(height, 1.0),
    }
}

/// Cross-axis geometry shared by every column of a paragraph.
struct ColumnGeometry {
    /// Line-height-controlled column advance the base glyphs centre on.
    base_width: f32,
    /// Annotation gutter on the left (`under`) side of the base band.
    under_gutter: f32,
    /// Annotation gutter on the right (`over`) side of the base band.
    over_gutter: f32,
}

impl ColumnGeometry {
    fn new(paragraph: &Paragraph) -> Self {
        let spans = paragraph.children();
        let line_height = if paragraph.line_height() > 0.0 {
            paragraph.line_height()
        } else {
            1.2
        };
        let max_font_size = spans.iter().map(|s| s.font_size).fold(12.0, f32::max);

        // Ruby reserves its configured-size gutter on the logical annotation
        // side. In vertical-rl, `over` is right and `under` is left.
        let ruby_gutter = |side| {
            spans
                .iter()
                .filter(|span| span.has_ruby() && span.ruby_side == side)
                .map(TextSpan::ruby_font_size)
                .fold(0.0, f32::max)
        };
        let ruby_over_gutter = ruby_gutter(RubySide::Over);
        // Emphasis takes the over (right) side. Auto-clearance spans with both
        // annotations stack there; under-side ruby stays separate.
        let emphasis_gutter = if spans.iter().any(|s| !s.text_emphasis.is_none()) {
            max_font_size * EMPHASIS_FONT_SCALE
        } else {
            0.0
        };
        let stacked = spans
            .iter()
            .any(|s| s.stacks_emphasis_outside_ruby() && !s.text_emphasis.is_none());
        let over_gutter = if stacked {
            ruby_over_gutter + emphasis_gutter
        } else {
            ruby_over_gutter.max(emphasis_gutter)
        };

        Self {
            base_width: max_font_size * line_height,
            under_gutter: ruby_gutter(RubySide::Under),
            over_gutter,
        }
    }

    fn column(&self) -> VerticalColumn {
        VerticalColumn {
            x: 0.0,
            width: self.under_gutter + self.base_width + self.over_gutter,
            base_offset: self.under_gutter,
            base_width: self.base_width,
        }
    }
}

/// The paragraph's cells in flow order, before spacing and placement, the
/// UTF-16 start of every span in the paragraph's layout text, and its warichu
/// notes. Notes break into pieces at the paragraph UTF-16 `warichu_splits`.
#[allow(clippy::too_many_arguments)]
fn build_paragraph_flow<'a>(
    fonts: &'a Fonts,
    paragraph_index: usize,
    paragraph: &'a Paragraph,
    transforms: &'a [AppliedTextTransform],
    bounds: Rect,
    warichu_splits: &[usize],
    runs: &mut Vec<ShapedRun>,
    paints: &mut Vec<skia::Paint>,
) -> (Vec<FlowCell>, Vec<usize>, Vec<WarichuNote<'a>>) {
    let mut flow = Vec::new();
    let mut span_starts = Vec::with_capacity(transforms.len());
    let mut notes: Vec<WarichuNote<'a>> = Vec::new();
    let mut note_members = Vec::new();
    let mut offset = 0usize;
    for (span_index, (span, transform)) in paragraph.children().iter().zip(transforms).enumerate() {
        span_starts.push(offset);
        if transform.text.is_empty() {
            continue;
        }
        paints.push(merge_fills(&span.fills, bounds));
        let span_cells = SpanCells::new(
            fonts,
            span,
            paragraph_index,
            span_index,
            paints.len() - 1,
            offset,
        );
        if span.warichu {
            note_members.push((span_cells, transform.text.as_str()));
        } else {
            flush_warichu_note(
                &mut note_members,
                &mut notes,
                warichu_splits,
                runs,
                &mut flow,
            );
            span_cells.push(&transform.text, runs, &mut flow);
        }
        offset += transform.text.encode_utf16().count();
    }
    flush_warichu_note(
        &mut note_members,
        &mut notes,
        warichu_splits,
        runs,
        &mut flow,
    );
    keep_transform_expansions_together(&mut flow, transforms, &span_starts);
    (flow, span_starts, notes)
}

/// Push the warichu note gathered in `members`, if any, and keep it.
fn flush_warichu_note<'a>(
    members: &mut Vec<(SpanCells<'a>, &'a str)>,
    notes: &mut Vec<WarichuNote<'a>>,
    warichu_splits: &[usize],
    runs: &mut Vec<ShapedRun>,
    flow: &mut Vec<FlowCell>,
) {
    if members.is_empty() {
        return;
    }
    let note = WarichuNote::new(std::mem::take(members));
    note.push(warichu_splits, runs, flow);
    notes.push(note);
}

/// Most warichu breaks planned per paragraph.
const MAX_WARICHU_SPLITS: usize = 64;

/// Next break that lets a warichu piece start where the planner left room
/// for it, as a paragraph UTF-16 offset.
fn next_warichu_split(
    notes: &[WarichuNote],
    flow: &[FlowCell],
    placements: &[(usize, f32)],
    splits: &[usize],
    max_height: f32,
) -> Option<usize> {
    warichu_overflows(flow, placements, max_height)
        .into_iter()
        .find_map(|(i, room)| {
            let start = flow[i].cell.start;
            let note = notes.iter().find(|note| note.contains(start))?;
            note.split_for_room(start, splits, room)
                .filter(|split| !splits.contains(split))
        })
}

/// Warichu pieces the planner moved whole to a later column, or that overrun
/// their column, with the room left for them where they should start: after
/// the cells kinsoku carried along, at the bottom of the previous column.
fn warichu_overflows(
    flow: &[FlowCell],
    placements: &[(usize, f32)],
    max_height: f32,
) -> Vec<(usize, f32)> {
    if !is_bounded(max_height) {
        return Vec::new();
    }
    let columns = placements.last().map_or(0, |(column, _)| column + 1);
    let mut column_used = vec![0.0f32; columns];
    let mut column_first = vec![usize::MAX; columns];
    for (i, (cell, (column, top))) in flow.iter().zip(placements).enumerate() {
        column_used[*column] = column_used[*column].max(top + cell.cell.extent);
        column_first[*column] = column_first[*column].min(i);
    }
    let mut overflows = Vec::new();
    for (i, (cell, (column, top))) in flow.iter().zip(placements).enumerate() {
        if !matches!(cell.cell.kind, CellKind::Warichu { .. }) || cell.shares_previous_box {
            continue;
        }
        if top + cell.cell.extent > max_height + FIT_TOLERANCE {
            overflows.push((i, max_height - top));
        } else if *column > 0 {
            let carried: f32 = flow[column_first[*column]..i]
                .iter()
                .map(|carried| carried.cell.extent)
                .sum();
            let room = max_height - column_used[column - 1] - carried;
            if room > FIT_TOLERANCE {
                overflows.push((i, room));
            }
        }
    }
    overflows
}
/// A CSS transform may expand one source character into several cells
/// (`ß` -> `SS`). Keep them in one column so the SVG fallback renders each
/// source slice once.
fn keep_transform_expansions_together(
    flow: &mut [FlowCell],
    transforms: &[AppliedTextTransform],
    span_starts: &[usize],
) {
    let source_range = |cell: &VerticalCell| {
        let start = span_starts[cell.span];
        transforms[cell.span].source_utf16_range(cell.start - start..cell.end - start)
    };
    for index in 1..flow.len() {
        let (previous, current) = (&flow[index - 1].cell, &flow[index].cell);
        flow[index].keep_with_previous =
            previous.span == current.span && source_range(previous) == source_range(current);
    }
}

/// Final flow-axis top of each cell: its planned offset plus the text-align
/// shift. Justify stretches every column but the last to fill a bounded wrap
/// height.
fn aligned_tops(
    flow: &[FlowCell],
    classes: &[Option<JapaneseClass>],
    pair_spacing_em: &[f32],
    placements: &[(usize, f32)],
    align: TextAlign,
    max_height: f32,
) -> Vec<f32> {
    let columns_used = placements.last().map_or(1, |(column, _)| column + 1);
    // A column's used length is its farthest cell bottom.
    let mut column_used = vec![0.0f32; columns_used];
    for (flow, (column, top)) in flow.iter().zip(placements) {
        column_used[*column] = column_used[*column].max(top + flow.cell.extent);
    }
    let expansion = if matches!(align, TextAlign::Justify) && is_bounded(max_height) {
        ordered_expansion_offsets(
            flow,
            classes,
            pair_spacing_em,
            placements,
            &column_used,
            max_height,
        )
    } else {
        vec![0.0; flow.len()]
    };
    placements
        .iter()
        .zip(expansion)
        .map(|((column, top), extra)| {
            top + align_offset_along_column(align, max_height, column_used[*column]) + extra
        })
        .collect()
}

/// Lay out the whole content vertically. Pure of global state: fonts come
/// through the provider/fallback arguments so native tests can supply
/// their own.
pub fn layout_vertical(
    text_content: &TextContent,
    max_height: f32,
    font_provider: &TypefaceFontProvider,
    fallback_mgr: FontMgr,
    fallback_families: &[String],
    bounds: Rect,
) -> VerticalLayout {
    let fonts = Fonts {
        provider: font_provider,
        fallback_mgr: &fallback_mgr,
        fallback_families,
    };
    let paragraphs = text_content.paragraphs();
    let span_transforms: Vec<Vec<AppliedTextTransform>> = paragraphs
        .iter()
        .map(|paragraph| {
            paragraph
                .children()
                .iter()
                .map(TextSpan::apply_text_transform_with_source_ranges)
                .collect()
        })
        .collect();
    let paragraph_utf16_boundaries: Vec<Vec<usize>> = span_transforms
        .iter()
        .map(|paragraph| {
            let mut boundaries = vec![0usize];
            for character in paragraph.iter().flat_map(|span| span.text.chars()) {
                let next = boundaries.last().copied().unwrap_or(0) + character.len_utf16();
                boundaries.push(next);
            }
            boundaries
        })
        .collect();
    let span_source_utf16_starts: Vec<Vec<usize>> = paragraphs
        .iter()
        .map(|paragraph| {
            let mut offset = 0usize;
            paragraph
                .children()
                .iter()
                .map(|span| {
                    let start = offset;
                    offset += span.text.encode_utf16().count();
                    start
                })
                .collect()
        })
        .collect();

    let mut runs: Vec<ShapedRun> = Vec::new();
    let mut paints: Vec<skia::Paint> = Vec::new();
    let mut cells: Vec<VerticalCell> = Vec::new();
    let mut columns: Vec<VerticalColumn> = Vec::new();
    let mut paragraph_columns: Vec<(usize, usize)> = Vec::new();
    let mut span_utf16_starts: Vec<Vec<usize>> = Vec::new();

    for (paragraph_index, paragraph) in paragraphs.iter().enumerate() {
        let transforms = &span_transforms[paragraph_index];
        let ruby_spans: Vec<bool> = paragraph
            .children()
            .iter()
            .map(TextSpan::has_ruby)
            .collect();

        // Plan, then break the first warichu piece that does not fit where
        // it starts and plan again, until every piece fits.
        let (run_mark, paint_mark) = (runs.len(), paints.len());
        let mut warichu_splits: Vec<usize> = Vec::new();
        let (flow, span_starts, ruby_units, classes, pair_spacing_em, placements) = loop {
            runs.truncate(run_mark);
            paints.truncate(paint_mark);
            let (mut flow, span_starts, notes) = build_paragraph_flow(
                &fonts,
                paragraph_index,
                paragraph,
                transforms,
                bounds,
                &warichu_splits,
                &mut runs,
                &mut paints,
            );
            let ruby_units = ruby_base_units(paragraph, transforms, &span_starts);

            apply_inter_script_spacing(&mut flow);
            let classes = flow_classes(&flow, &ruby_spans);
            shed_punctuation_aki(&mut flow, &classes);
            materialize_explicit_pair_spacing(&mut flow, &classes);
            grow_ruby_bases(&mut flow, &ruby_units);
            let mut pair_spacing_em = preferred_pair_spacing(&classes);
            apply_ordered_oikomi(&mut flow, &classes, &mut pair_spacing_em, max_height);
            let placements =
                plan_with_edge_trimming(&mut flow, &classes, &mut pair_spacing_em, max_height);
            let split = if warichu_splits.len() < MAX_WARICHU_SPLITS {
                next_warichu_split(&notes, &flow, &placements, &warichu_splits, max_height)
            } else {
                None
            };
            match split {
                Some(split) => warichu_splits.push(split),
                None => {
                    break (
                        flow,
                        span_starts,
                        ruby_units,
                        classes,
                        pair_spacing_em,
                        placements,
                    )
                }
            }
        };
        let tops = aligned_tops(
            &flow,
            &classes,
            &pair_spacing_em,
            &placements,
            paragraph.text_align(),
            max_height,
        );

        let columns_used = placements.last().map_or(1, |(column, _)| column + 1);
        let column_base = columns.len();
        columns.extend(std::iter::repeat_n(
            ColumnGeometry::new(paragraph).column(),
            columns_used,
        ));
        paragraph_columns.push((column_base, column_base + columns_used));

        let paragraph_cell_start = cells.len();
        for ((flow, (column, _)), top) in flow.into_iter().zip(placements).zip(tops) {
            let mut cell = VerticalCell {
                column: column_base + column,
                top,
                ..flow.cell
            };
            if flow.shares_previous_box {
                if let Some(piece) = cells.last() {
                    cell.top = piece.top;
                    cell.extent = piece.extent;
                    cell.minimum_oikomi_extent = piece.minimum_oikomi_extent;
                    cell.ink_top = piece.ink_top;
                    cell.ink_bottom = piece.ink_bottom;
                }
            }
            cells.push(cell);
        }
        spread_ruby_base_cells(&mut cells[paragraph_cell_start..], &ruby_units, max_height);
        span_utf16_starts.push(span_starts);
    }

    // Columns advance right->left: column 0 is the rightmost.
    let width: f32 = columns.iter().map(|c| c.width).sum();
    let mut right = width;
    for column in columns.iter_mut() {
        right -= column.width;
        column.x = right;
    }

    let (ruby_runs, ruby_cells) = layout_ruby(text_content, &cells, &fonts);
    let (emphasis_runs, emphasis_marks) = layout_emphasis(
        text_content,
        &cells,
        &span_utf16_starts,
        &span_transforms,
        &fonts,
    );

    let height = cells
        .iter()
        .map(|c| c.top + c.extent)
        .fold(0.0f32, f32::max);

    VerticalLayout {
        runs,
        paints,
        cells,
        columns,
        ruby_runs,
        ruby_cells,
        emphasis_runs,
        emphasis_marks,
        paragraph_columns,
        span_utf16_starts,
        span_source_utf16_starts,
        span_transforms,
        paragraph_utf16_boundaries,
        width,
        height,
    }
}

/// Lay out with the render state's font store.
fn layout_from_content(text_content: &TextContent, max_height: f32) -> VerticalLayout {
    let font_provider = get_resources().fonts.font_provider();
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    layout_vertical(
        text_content,
        max_height,
        font_provider,
        fallback_mgr,
        &fallback_families,
        text_content.bounds(),
    )
}

/// Production entry point: lay out the content for a box `height` tall.
pub fn layout_for_box(text_content: &TextContent, height: f32) -> VerticalLayout {
    layout_from_content(text_content, wrap_height(text_content, height))
}

/// Content size (width, height) of the vertical layout for a box `height`
/// tall, for auto-sizing.
pub fn measure_content(text_content: &TextContent, height: f32) -> (f32, f32) {
    let layout = layout_for_box(text_content, height);
    (layout.width, layout.height)
}

#[cfg(test)]
mod tests {
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::TextOrientation;

    fn warichu_span(text: &str) -> TextSpan {
        TextSpan {
            warichu: true,
            text_orientation: TextOrientation::Upright,
            ..make_span(text)
        }
    }

    /// Cells that start a warichu piece, in flow order.
    fn warichu_pieces(layout: &VerticalLayout) -> Vec<&VerticalCell> {
        let mut pieces: Vec<&VerticalCell> = Vec::new();
        for cell in &layout.cells {
            if !matches!(cell.kind, CellKind::Warichu { .. }) {
                continue;
            }
            let same_box = pieces
                .last()
                .is_some_and(|piece| piece.column == cell.column && piece.top == cell.top);
            if !same_box {
                pieces.push(cell);
            }
        }
        pieces
    }

    #[test]
    fn warichu_breaks_at_the_column_end() {
        // Four ems of base text leave two ems; ten half-size characters need
        // two and a half, so four per sub-line stay and the rest wrap.
        let budget = 6.0 * EM;
        let content = spans_content(
            vec![make_span("くくくく"), warichu_span("あくあくあくあくあく")],
            budget,
        );
        let layout = layout_with_height(&provider(VMTX_TEST_FONT), &content, budget);
        let pieces = warichu_pieces(&layout);
        assert_eq!(pieces.len(), 2, "the note breaks into two pieces");
        let (first, second) = (pieces[0], pieces[1]);
        assert_eq!(first.column, 0);
        assert!((first.top - 4.0 * EM).abs() < 0.01);
        assert!(first.top + first.extent <= budget + 0.01);
        assert_eq!(first.end - first.start, 8, "four characters per sub-line");
        assert_eq!(second.column, 1);
        assert_eq!(second.top, 0.0);
        assert_eq!((second.start, second.end), (first.end, 14));
    }

    #[test]
    fn adjacent_warichu_spans_form_one_note() {
        let content = spans_content(vec![warichu_span("くあく"), warichu_span("あくあ")], 400.0);
        let layout = layout_with_height(&provider(VMTX_TEST_FONT), &content, 400.0);
        assert_eq!(warichu_pieces(&layout).len(), 1, "one note, one piece");
        let [first, second] = &layout.cells[..] else {
            panic!("one cell per span, got {}", layout.cells.len());
        };
        assert_eq!((first.span, second.span), (0, 1));
        assert_eq!((first.top, first.extent), (second.top, second.extent));
        let (
            CellKind::Warichu {
                first_chars: first_span_chars,
                first_extent,
                ..
            },
            CellKind::Warichu {
                first_chars: second_span_chars,
                second_top,
                second_extent,
                ..
            },
        ) = (first.kind, second.kind)
        else {
            panic!("expected warichu cells");
        };
        assert_eq!(
            first_span_chars, 3,
            "the first span fills the first sub-line"
        );
        assert_eq!(second_span_chars, 0, "the second span fills the second");
        assert_eq!(
            second_top, 0.0,
            "the second sub-line starts at the piece top"
        );
        assert!((first_extent - second_extent).abs() < 0.01);
        assert!(
            (first.extent - 3.0 * EM / 2.0).abs() < 0.01,
            "three half-size characters per sub-line, got {}",
            first.extent
        );
    }

    #[test]
    fn layout_cells_tile_the_text() {
        let text = "縦書きのAB12テスト。";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        let mut expected = 0usize;
        for cell in &layout.cells {
            assert_eq!(cell.paragraph, 0);
            assert_eq!(cell.start, expected, "cells must tile without gaps");
            assert!(cell.end > cell.start);
            expected = cell.end;
        }
        assert_eq!(expected, text.encode_utf16().count());
    }

    #[test]
    fn layout_columns_respect_wrap_height() {
        let content = make_content(&["あいうえおかきくけこ"], 100.0);
        let layout = layout_content(&content, 60.0);

        assert!(layout.columns.len() > 1, "content must wrap into columns");
        for column_index in 0..layout.columns.len() {
            let bottom = layout
                .cells
                .iter()
                .filter(|c| c.column == column_index)
                .map(|c| c.top + c.extent)
                .fold(0.0f32, f32::max);
            assert!(bottom <= 60.0 + 0.01, "column overflows the wrap height");
        }
        // Columns advance leftward: column 0 is the rightmost.
        assert!(layout.columns[0].x > layout.columns[1].x);
        let total: f32 = layout.columns.iter().map(|c| c.width).sum();
        assert!((layout.width - total).abs() < 0.01);
    }

    #[test]
    fn layout_each_paragraph_starts_a_new_column() {
        let content = make_content(&["あい", "うえ"], 1000.0);
        let layout = layout_content(&content, 1000.0);

        assert_eq!(layout.paragraph_columns.len(), 2);
        let (p0_start, p0_end) = layout.paragraph_columns[0];
        let (p1_start, _) = layout.paragraph_columns[1];
        assert_eq!(p0_start, 0);
        assert_eq!(p0_end, p1_start);
        assert!(layout
            .cells
            .iter()
            .all(|c| (c.paragraph == 0) == (c.column < p0_end)));
    }

    #[test]
    fn layout_empty_paragraph_still_takes_a_column() {
        let content = make_content(&["あ", "", "い"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        assert_eq!(layout.columns.len(), 3);
        assert_eq!(layout.paragraph_columns[1], (1, 2));
    }

    #[test]
    fn block_axis_alignment_maps_start_center_end_to_right_center_left() {
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Top), 160.0);
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Center), 80.0);
        assert_eq!(block_axis_offset(200.0, 40.0, VerticalAlign::Bottom), 0.0);
        assert_eq!(block_axis_offset(20.0, 40.0, VerticalAlign::Top), -20.0);
        assert_eq!(block_axis_offset(20.0, 40.0, VerticalAlign::Center), -10.0);
        assert_eq!(block_axis_offset(20.0, 40.0, VerticalAlign::Bottom), 0.0);
    }

    #[test]
    fn wrapped_vertical_content_grows_across_columns() {
        let content = make_content(&["あいうえおかきくけこ"], 60.0);
        let layout = layout_content(&content, 60.0);
        assert!(layout.columns.len() > 1);
        assert!(layout.width > layout.columns[0].width);
        assert!(layout.height <= 60.0 + 0.01);
    }
}
