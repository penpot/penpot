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
use super::cells::{Fonts, SpanCells};
use super::flow::{
    align_offset_along_column, apply_inter_script_spacing, apply_ordered_oikomi, flow_classes,
    is_bounded, materialize_explicit_pair_spacing, ordered_expansion_offsets,
    plan_with_edge_trimming, preferred_pair_spacing, shed_punctuation_aki, FlowCell,
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
    Warichu {
        /// First `first_count` runs are the right sub-line, the rest the left.
        run_start: usize,
        run_count: usize,
        first_count: usize,
        /// UTF-16 length of the first sub-line (split point from `start`).
        first_chars: usize,
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
/// vertical-rl.
pub fn block_axis_offset(container_width: f32, content_width: f32, align: VerticalAlign) -> f32 {
    let slack = (container_width - content_width).max(0.0);
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

/// The paragraph's cells in flow order, before spacing and placement, plus
/// the UTF-16 start of every span in the paragraph's layout text.
fn build_paragraph_flow(
    fonts: &Fonts,
    paragraph_index: usize,
    paragraph: &Paragraph,
    transforms: &[AppliedTextTransform],
    bounds: Rect,
    runs: &mut Vec<ShapedRun>,
    paints: &mut Vec<skia::Paint>,
) -> (Vec<FlowCell>, Vec<usize>) {
    let mut flow = Vec::new();
    let mut span_starts = Vec::with_capacity(transforms.len());
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
        span_cells.push(&transform.text, runs, &mut flow);
        offset += transform.text.encode_utf16().count();
    }
    keep_transform_expansions_together(&mut flow, transforms, &span_starts);
    (flow, span_starts)
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
        let (mut flow, span_starts) = build_paragraph_flow(
            &fonts,
            paragraph_index,
            paragraph,
            transforms,
            bounds,
            &mut runs,
            &mut paints,
        );
        let ruby_units = ruby_base_units(paragraph, transforms, &span_starts);
        let ruby_spans: Vec<bool> = paragraph
            .children()
            .iter()
            .map(TextSpan::has_ruby)
            .collect();

        apply_inter_script_spacing(&mut flow);
        let classes = flow_classes(&flow, &ruby_spans);
        shed_punctuation_aki(&mut flow, &classes);
        materialize_explicit_pair_spacing(&mut flow, &classes);
        grow_ruby_bases(&mut flow, &ruby_units);
        let mut pair_spacing_em = preferred_pair_spacing(&classes);
        apply_ordered_oikomi(&mut flow, &classes, &mut pair_spacing_em, max_height);
        let placements =
            plan_with_edge_trimming(&mut flow, &classes, &mut pair_spacing_em, max_height);
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
            cells.push(VerticalCell {
                column: column_base + column,
                top,
                ..flow.cell
            });
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
        assert_eq!(block_axis_offset(20.0, 40.0, VerticalAlign::Top), 0.0);
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
