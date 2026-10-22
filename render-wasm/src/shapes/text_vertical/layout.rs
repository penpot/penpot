use std::cell::OnceCell;

use skia_safe::{
    self as skia,
    textlayout::{TextDecoration, TypefaceFontProvider},
    FontMgr,
};

use crate::globals::get_resources;
use crate::math::Rect;
use crate::shapes::japanese::JapaneseClass;
use crate::shapes::text_policy::TextLayoutPolicy;
use crate::shapes::{
    merge_fills, AppliedTextTransform, FontFeatures, GrowType, LineAdjustment, Paragraph, RubySide,
    TextAlign, TextContent, TextSpan, VerticalAlign,
};
use crate::utils::get_fallback_fonts;

use super::annotations::{
    grow_ruby_bases, layout_emphasis, layout_ruby, ruby_base_units, ruby_rooms,
    set_ruby_overhang_rooms, spread_ruby_base_cells, EmphasisMark, RubyBaseUnit, RubyCell,
    RubyRooms,
};
use super::cells::{Fonts, SpanCells, WarichuNote};
use super::flow::{
    aki_classes, align_offset_along_column, apply_inter_script_spacing, apply_ordered_oikomi,
    apply_policy_spacing, flow_classes, is_bounded, materialize_explicit_pair_spacing,
    ordered_expansion_offsets, plan_policy_columns, plan_with_edge_trimming, policy_aligned_tops,
    preferred_pair_spacing, shed_punctuation_aki, FlowCell, FIT_TOLERANCE,
};
use super::paint::LayoutDraws;
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
        /// Horizontal squeeze that fits the runs into one em; glyphs keep
        /// their full height.
        h_scale: f32,
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
#[derive(Clone)]
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
    pub underline_side: RubySide,
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
    /// Text offsets of each paragraph.
    pub text_maps: Vec<ParagraphTextMap>,
    pub width: f32,
    pub height: f32,
    /// Glyph draws, built on the first paint.
    pub(super) draws: OnceCell<LayoutDraws>,
}

/// Text offsets of one laid-out paragraph, which map cells back to the
/// source text.
pub struct ParagraphTextMap {
    /// Transformed text of each span, with its source ownership.
    pub span_transforms: Vec<AppliedTextTransform>,
    /// UTF-16 start of each span in the paragraph's transformed text.
    pub span_starts: Vec<usize>,
    /// UTF-16 start of each span in the paragraph's source text.
    pub span_source_starts: Vec<usize>,
    /// UTF-16 offset of each scalar boundary of the transformed text, for
    /// editor positions.
    pub utf16_boundaries: Vec<usize>,
}

impl ParagraphTextMap {
    fn new(paragraph: &Paragraph) -> Self {
        let span_transforms: Vec<AppliedTextTransform> = paragraph
            .children()
            .iter()
            .map(TextSpan::apply_text_transform_with_source_ranges)
            .collect();
        let span_starts = running_starts(
            span_transforms
                .iter()
                .map(|transform| transform.text.encode_utf16().count()),
        );
        let span_source_starts = running_starts(
            paragraph
                .children()
                .iter()
                .map(|span| span.text.encode_utf16().count()),
        );
        let mut utf16_boundaries = vec![0usize];
        let mut offset = 0usize;
        for character in span_transforms.iter().flat_map(|span| span.text.chars()) {
            offset += character.len_utf16();
            utf16_boundaries.push(offset);
        }
        Self {
            span_transforms,
            span_starts,
            span_source_starts,
            utf16_boundaries,
        }
    }
}

/// Start of each item when items of `lengths` follow one another.
fn running_starts(lengths: impl Iterator<Item = usize>) -> Vec<usize> {
    lengths
        .scan(0usize, |offset, length| {
            let start = *offset;
            *offset += length;
            Some(start)
        })
        .collect()
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

        // Under automatic clearance, ruby reserves its configured-size gutter
        // on the logical annotation side. In vertical-rl, `over` is right and
        // `under` is left. `None` keeps the column advance, as horizontal
        // keeps the line height.
        let gutter = |side| {
            let ruby = spans
                .iter()
                .filter(|span| {
                    span.annotation_clearance.is_auto() && span.has_ruby() && span.ruby_side == side
                })
                .map(TextSpan::ruby_font_size)
                .fold(0.0, f32::max);
            let emphasis = spans
                .iter()
                .filter(|span| {
                    span.annotation_clearance.is_auto()
                        && !span.text_emphasis.is_none()
                        && span.emphasis_side(true) == side
                })
                .map(|span| span.font_size * crate::shapes::text_japanese::EMPHASIS_FONT_SCALE)
                .fold(0.0, f32::max);
            let decoration = spans
                .iter()
                .map(|span| span.font_size * span.decoration_room_em(side, true))
                .fold(0.0, f32::max);
            // Keep the original mixed-font gutter: when any span stacks the
            // two annotations, reserve their independent maximum sizes.
            let stacked = spans.iter().any(|span| {
                !span.text_emphasis.is_none()
                    && span.ruby_side == side
                    && span.stacks_emphasis_outside_ruby(true)
            });
            decoration
                + if stacked {
                    ruby + emphasis
                } else {
                    ruby.max(emphasis)
                }
        };
        Self {
            base_width: max_font_size * line_height,
            under_gutter: gutter(RubySide::Under),
            over_gutter: gutter(RubySide::Over),
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

/// A run of the paragraph flow: shaped cells, or the warichu note at an
/// index of `ParagraphCells::notes`.
enum FlowSegment {
    Shaped(Vec<FlowCell>),
    Note(usize),
}

/// A paragraph shaped once for warichu planning. Spans outside warichu are
/// shaped up front; each plan only reshapes the notes.
struct ParagraphCells<'a> {
    paragraph: &'a Paragraph,
    text_map: &'a ParagraphTextMap,
    segments: Vec<FlowSegment>,
    notes: Vec<WarichuNote<'a>>,
}

impl<'a> ParagraphCells<'a> {
    /// Shapes the spans outside warichu into `runs` and adds one paint per
    /// span to `paints`.
    fn new(
        fonts: &'a Fonts,
        paragraph_index: usize,
        paragraph: &'a Paragraph,
        text_map: &'a ParagraphTextMap,
        bounds: Rect,
        runs: &mut Vec<ShapedRun>,
        paints: &mut Vec<skia::Paint>,
    ) -> Self {
        let mut segments = Vec::new();
        let mut notes: Vec<WarichuNote<'a>> = Vec::new();
        let mut note_members = Vec::new();
        let mut flush_note = |members: &mut Vec<_>, segments: &mut Vec<FlowSegment>| {
            if !members.is_empty() {
                segments.push(FlowSegment::Note(notes.len()));
                notes.push(WarichuNote::new(std::mem::take(members)));
            }
        };
        for (span_index, (span, transform)) in paragraph
            .children()
            .iter()
            .zip(&text_map.span_transforms)
            .enumerate()
        {
            let offset = text_map.span_starts[span_index];
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
                flush_note(&mut note_members, &mut segments);
                let mut cells = Vec::new();
                span_cells.push(&transform.text, runs, &mut cells);
                segments.push(FlowSegment::Shaped(cells));
            }
        }
        flush_note(&mut note_members, &mut segments);
        Self {
            paragraph,
            text_map,
            segments,
            notes,
        }
    }

    /// The paragraph's cells in flow order, before spacing and placement,
    /// with notes broken into pieces at the paragraph UTF-16
    /// `warichu_splits`. Note runs go to the end of `runs`.
    fn flow(&self, warichu_splits: &[usize], runs: &mut Vec<ShapedRun>) -> Vec<FlowCell> {
        let mut flow = Vec::new();
        for segment in &self.segments {
            match segment {
                FlowSegment::Shaped(cells) => flow.extend(cells.iter().cloned()),
                FlowSegment::Note(note) => self.notes[*note].push(warichu_splits, runs, &mut flow),
            }
        }
        keep_transform_expansions_together(&mut flow, self.text_map);
        keep_ruby_bases_together(&mut flow, self.paragraph);
        flow
    }
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
fn keep_transform_expansions_together(flow: &mut [FlowCell], text_map: &ParagraphTextMap) {
    let source_range = |cell: &VerticalCell| {
        let start = text_map.span_starts[cell.span];
        text_map.span_transforms[cell.span].source_utf16_range(cell.start - start..cell.end - start)
    };
    for index in 1..flow.len() {
        let (previous, current) = (&flow[index - 1].cell, &flow[index].cell);
        flow[index].keep_with_previous =
            previous.span == current.span && source_range(previous) == source_range(current);
    }
}

/// A span reading is group ruby: its base moves between columns whole, so
/// the reading never splits.
fn keep_ruby_bases_together(flow: &mut [FlowCell], paragraph: &Paragraph) {
    let spans = paragraph.children();
    for index in 1..flow.len() {
        let span = flow[index].cell.span;
        if flow[index - 1].cell.span == span && spans.get(span).is_some_and(TextSpan::has_ruby) {
            flow[index].keep_with_previous = true;
        }
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

/// A paragraph's flow after spacing and column planning.
struct PlannedParagraph {
    policy: TextLayoutPolicy,
    text: String,
    flow: Vec<FlowCell>,
    ruby_units: Vec<RubyBaseUnit>,
    classes: Vec<Option<JapaneseClass>>,
    pair_spacing_em: Vec<f32>,
    /// (column, offset from the column top) of each flow cell.
    placements: Vec<(usize, f32)>,
}

impl PlannedParagraph {
    /// Plans the flow, then breaks the first warichu piece that does not fit
    /// where it starts and plans again, until every piece fits. Note runs go
    /// to the end of `runs`.
    fn new(
        cells: &ParagraphCells,
        fonts: &Fonts,
        max_height: f32,
        line_adjustment: LineAdjustment,
        runs: &mut Vec<ShapedRun>,
    ) -> Self {
        let spans = cells.paragraph.children();
        let ruby_spans: Vec<bool> = spans.iter().map(TextSpan::has_ruby).collect();
        let vpal_spans: Vec<bool> = spans
            .iter()
            .map(|span| span.font_features == FontFeatures::Vpal)
            .collect();
        let ruby_units = ruby_base_units(
            cells.paragraph,
            &cells.text_map.span_transforms,
            &cells.text_map.span_starts,
            fonts,
        );
        let run_mark = runs.len();
        let mut warichu_splits: Vec<usize> = Vec::new();
        loop {
            runs.truncate(run_mark);
            let mut flow = cells.flow(&warichu_splits, runs);
            let mut ruby_units = ruby_units.clone();

            let policy = cells.paragraph.layout_policy();
            if policy != TextLayoutPolicy::Japanese {
                grow_ruby_bases(&mut flow, &ruby_units);
                let text: String = cells
                    .text_map
                    .span_transforms
                    .iter()
                    .map(|t| t.text.as_str())
                    .collect();
                apply_policy_spacing(&mut flow, &text, policy);
                let placements = plan_policy_columns(&flow, &text, policy, max_height);
                return Self {
                    policy,
                    text,
                    classes: vec![None; flow.len()],
                    pair_spacing_em: vec![0.0; flow.len().saturating_sub(1)],
                    flow,
                    ruby_units,
                    placements,
                };
            }

            apply_inter_script_spacing(&mut flow);
            let classes = flow_classes(&flow, &ruby_spans);
            let aki = aki_classes(&flow, &classes, &vpal_spans);
            shed_punctuation_aki(&mut flow, &aki);
            materialize_explicit_pair_spacing(&mut flow, &classes);
            set_ruby_overhang_rooms(&flow, &mut ruby_units);
            grow_ruby_bases(&mut flow, &ruby_units);
            let mut pair_spacing_em = preferred_pair_spacing(&classes);
            apply_ordered_oikomi(
                &mut flow,
                &classes,
                &mut pair_spacing_em,
                max_height,
                line_adjustment,
            );
            let placements =
                plan_with_edge_trimming(&mut flow, &classes, &mut pair_spacing_em, max_height);
            let split = if warichu_splits.len() < MAX_WARICHU_SPLITS {
                next_warichu_split(
                    &cells.notes,
                    &flow,
                    &placements,
                    &warichu_splits,
                    max_height,
                )
            } else {
                None
            };
            match split {
                Some(split) => warichu_splits.push(split),
                None => {
                    return Self {
                        policy,
                        text: String::new(),
                        flow,
                        ruby_units,
                        classes,
                        pair_spacing_em,
                        placements,
                    }
                }
            }
        }
    }

    fn columns_used(&self) -> usize {
        self.placements.last().map_or(1, |(column, _)| column + 1)
    }

    /// Final cells, aligned along their columns, numbered from `column_base`.
    /// A later span's cell of a warichu piece takes the piece's box.
    fn place(&self, align: TextAlign, max_height: f32, column_base: usize) -> Vec<VerticalCell> {
        let align =
            if self.policy == TextLayoutPolicy::KoreanSpacesOnly && align == TextAlign::Justify {
                TextAlign::Left
            } else {
                align
            };
        let tops = if self.policy == TextLayoutPolicy::Japanese && align != TextAlign::Distribute {
            aligned_tops(
                &self.flow,
                &self.classes,
                &self.pair_spacing_em,
                &self.placements,
                align,
                max_height,
            )
        } else {
            policy_aligned_tops(
                &self.flow,
                &self.text,
                self.policy,
                &self.placements,
                align,
                max_height,
            )
        };
        let mut cells: Vec<VerticalCell> = Vec::with_capacity(self.flow.len());
        for ((flow, (column, _)), top) in self.flow.iter().zip(&self.placements).zip(tops) {
            let mut cell = VerticalCell {
                column: column_base + column,
                top,
                ..flow.cell.clone()
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
        cells
    }
}

/// Sets each column's x, right to left from column 0, and returns the total
/// width.
fn place_columns_right_to_left(columns: &mut [VerticalColumn]) -> f32 {
    let width: f32 = columns.iter().map(|column| column.width).sum();
    let mut right = width;
    for column in columns.iter_mut() {
        right -= column.width;
        column.x = right;
    }
    width
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
    let text_maps: Vec<ParagraphTextMap> = paragraphs.iter().map(ParagraphTextMap::new).collect();

    let mut runs: Vec<ShapedRun> = Vec::new();
    let mut paints: Vec<skia::Paint> = Vec::new();
    let mut cells: Vec<VerticalCell> = Vec::new();
    let mut columns: Vec<VerticalColumn> = Vec::new();
    let mut paragraph_columns: Vec<(usize, usize)> = Vec::new();
    let mut ruby_rooms_by_span = RubyRooms::new();

    for (paragraph_index, (paragraph, text_map)) in paragraphs.iter().zip(&text_maps).enumerate() {
        let paragraph_cells = ParagraphCells::new(
            &fonts,
            paragraph_index,
            paragraph,
            text_map,
            bounds,
            &mut runs,
            &mut paints,
        );
        let planned = PlannedParagraph::new(
            &paragraph_cells,
            &fonts,
            max_height,
            text_content.line_adjustment(),
            &mut runs,
        );

        let column_base = columns.len();
        let columns_used = planned.columns_used();
        columns.extend(std::iter::repeat_n(
            ColumnGeometry::new(paragraph).column(),
            columns_used,
        ));
        paragraph_columns.push((column_base, column_base + columns_used));

        let mut placed = planned.place(paragraph.text_align(), max_height, column_base);
        spread_ruby_base_cells(&mut placed, &planned.ruby_units, max_height);
        cells.extend(placed);
        ruby_rooms_by_span.extend(ruby_rooms(paragraph_index, &planned.ruby_units));
    }

    let width = place_columns_right_to_left(&mut columns);
    let (ruby_runs, ruby_cells) = layout_ruby(text_content, &cells, &fonts, &ruby_rooms_by_span);
    let (emphasis_runs, emphasis_marks) =
        layout_emphasis(text_content, &cells, &runs, &text_maps, &fonts);
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
        text_maps,
        width,
        height,
        draws: OnceCell::new(),
    }
}

/// Lay out with the render state's font store; span paints resolve against
/// `bounds`.
fn layout_from_content(
    text_content: &TextContent,
    max_height: f32,
    bounds: Rect,
) -> VerticalLayout {
    let font_provider = get_resources().fonts.font_provider();
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    layout_vertical(
        text_content,
        max_height,
        font_provider,
        fallback_mgr,
        &fallback_families,
        bounds,
    )
}

/// Lay out the content for a box `height` tall, against its stored bounds.
#[cfg(test)]
pub(super) fn layout_for_box(text_content: &TextContent, height: f32) -> VerticalLayout {
    layout_from_content(
        text_content,
        wrap_height(text_content, height),
        text_content.bounds(),
    )
}

/// Lay out the content for the box `rect`: its height is the column-wrap
/// budget and span paints resolve against it. Production code goes through
/// the cached `TextContent::vertical_layout`.
pub fn layout_for_rect(text_content: &TextContent, rect: &Rect) -> VerticalLayout {
    layout_from_content(
        text_content,
        wrap_height(text_content, rect.height()),
        *rect,
    )
}

#[cfg(test)]
mod tests {
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::TextOrientation;

    fn cjk_layout(
        texts: &[&str],
        language: &str,
        wrap: crate::shapes::text_policy::KoreanWrap,
        align: TextAlign,
        height: f32,
    ) -> VerticalLayout {
        let mut paragraph = vertical_paragraph(
            texts.iter().map(|text| make_span(text)).collect(),
            align,
            1.0,
        );
        paragraph.set_language(language.to_string());
        paragraph.set_korean_wrap(wrap);
        let content = content_of(vec![paragraph], height, GrowType::Fixed);
        layout_with_height(
            &provider(include_bytes!("../../fonts/cjk-locl-test.ttf")),
            &content,
            height,
        )
    }

    #[test]
    fn distributed_hangul_fills_the_final_column_across_styles() {
        use crate::shapes::text_policy::KoreanWrap;
        let layout = cjk_layout(
            &["기획", "편집"],
            "ko",
            KoreanWrap::KeepWords,
            TextAlign::Distribute,
            310.0,
        );
        assert_eq!(layout.cells.len(), 4);
        assert!(layout.cells.iter().all(|cell| cell.column == 0));
        let last = layout.cells.last().unwrap();
        assert!((last.top + last.extent - 310.0).abs() < 0.01);
        let steps: Vec<_> = layout
            .cells
            .windows(2)
            .map(|pair| pair[1].top - pair[0].top)
            .collect();
        assert!(steps
            .windows(2)
            .all(|pair| (pair[1] - pair[0]).abs() < 0.01));
    }

    #[test]
    fn boxes_shorter_than_atomic_units_keep_closing_punctuation_with_its_base() {
        use crate::shapes::text_policy::KoreanWrap;
        for (language, wrap) in [
            ("zh-Hans-CN", KoreanWrap::Syllable),
            ("zh-Hant-TW", KoreanWrap::Syllable),
            ("zh-Hant-HK", KoreanWrap::Syllable),
            ("ko", KoreanWrap::Syllable),
            ("ko", KoreanWrap::KeepWords),
        ] {
            for height in [1.0, 10.0, 19.0, 20.0, 30.0, 39.0] {
                let layout = cjk_layout(&["文", "。"], language, wrap, TextAlign::Left, height);
                assert_eq!(layout.cells.len(), 2);
                assert_eq!(
                    layout.cells[0].column, layout.cells[1].column,
                    "{language} {wrap:?} {height}px: an indivisible unit overflows together"
                );
                for cell in &layout.cells {
                    assert!(cell.top.is_finite());
                    assert!(cell.extent.is_finite() && cell.extent > 0.0);
                }
            }
        }
    }

    #[test]
    fn korean_emergency_wrap_keeps_closing_punctuation_with_its_base() {
        use crate::shapes::text_policy::KoreanWrap;
        let layout = cjk_layout(
            &["한한", "한한", "。"],
            "ko",
            KoreanWrap::KeepWords,
            TextAlign::Left,
            EM * 2.1,
        );
        let cells = &layout.cells;
        assert!(cells.last().unwrap().column > 0);
        assert_eq!(cells[cells.len() - 1].column, cells[cells.len() - 2].column);
        assert!(cells.iter().all(|cell| cell.extent > 0.0));
    }

    #[test]
    fn regional_chinese_compression_and_justification_change_geometry() {
        use crate::shapes::text_policy::KoreanWrap;
        let cn = cjk_layout(
            &["文，", "。文"],
            "zh-Hans-CN",
            KoreanWrap::Syllable,
            TextAlign::Left,
            EM * 10.0,
        );
        let tw = cjk_layout(
            &["文，", "。文"],
            "zh-Hant-TW",
            KoreanWrap::Syllable,
            TextAlign::Left,
            EM * 10.0,
        );
        let end = |layout: &VerticalLayout| {
            layout.cells.last().unwrap().top + layout.cells.last().unwrap().extent
        };
        assert!((end(&tw) - end(&cn) - EM * 0.5).abs() < 0.01);
        let justified = cjk_layout(
            &["文文文文"],
            "zh-Hant-TW",
            KoreanWrap::Syllable,
            TextAlign::Justify,
            EM * 2.6,
        );
        assert!(justified.cells[1].top > EM + 1.0);
        assert_eq!(justified.cells[3].top, EM, "last column remains unexpanded");
    }

    #[test]
    fn traditional_chinese_hangs_only_a_single_comma_in_vertical_text() {
        use crate::shapes::text_policy::KoreanWrap;
        let single = cjk_layout(
            &["文文，文"],
            "zh-Hant-TW",
            KoreanWrap::Syllable,
            TextAlign::Left,
            EM * 2.6,
        );
        assert_eq!(single.cells[2].column, 0);
        assert_eq!(single.cells[3].column, 1);
        let paired = cjk_layout(
            &["文文，，文"],
            "zh-Hant-TW",
            KoreanWrap::Syllable,
            TextAlign::Left,
            EM * 2.6,
        );
        assert!(paired.cells[2].column > 0);
        assert_eq!(paired.cells[1].column, paired.cells[2].column);
    }

    #[test]
    fn word_mode_preserves_words_and_decomposed_syllables_across_styles() {
        use crate::shapes::text_policy::KoreanWrap;
        let layout = cjk_layout(
            &["한글 ", "한", "국어"],
            "ko",
            KoreanWrap::KeepWords,
            TextAlign::Left,
            EM * 3.6,
        );
        let word: Vec<_> = layout.cells.iter().filter(|c| c.start >= 3).collect();
        assert!(word.iter().all(|c| c.column == 1));
        let layout = cjk_layout(
            &["ᄒ", "ᅡᆫ한。"],
            "ko",
            KoreanWrap::KeepWords,
            TextAlign::Left,
            EM * 4.1,
        );
        for unit in [0..3, 3..7] {
            let cells: Vec<_> = layout
                .cells
                .iter()
                .filter(|c| unit.contains(&c.start))
                .collect();
            assert!(cells.iter().all(|c| c.column == cells[0].column));
        }
    }

    #[test]
    fn explicit_profiles_change_wrapping_without_japanese_spacing() {
        let columns = |language: &str, spaces_only: bool, texts: &[&str]| {
            let mut paragraph = vertical_paragraph(
                texts.iter().map(|text| make_span(text)).collect(),
                TextAlign::Left,
                1.0,
            );
            paragraph.set_language(language.to_string());
            paragraph.set_korean_wrap(if spaces_only {
                crate::shapes::text_policy::KoreanWrap::SpacesOnly
            } else {
                crate::shapes::text_policy::KoreanWrap::Syllable
            });
            let content = content_of(vec![paragraph], EM * 2.1, GrowType::Fixed);
            let layout = layout_with_height(
                &provider(include_bytes!("../../fonts/cjk-locl-test.ttf")),
                &content,
                EM * 2.1,
            );
            (
                layout
                    .cells
                    .iter()
                    .map(|cell| (cell.start, cell.column))
                    .collect::<Vec<_>>(),
                layout,
            )
        };
        // Small kana stay attached in Japanese only; Chinese uses its own
        // basic punctuation policy even when the fixture contains kana.
        let (ja, _) = columns("ja", false, &["文あっ文"]);
        let (zh, _) = columns("zh-Hans", false, &["文あっ文"]);
        assert_ne!(ja, zh);
        let (syllables, _) = columns("ko", false, &["한", "국어 문장"]);
        let (words, layout) = columns("ko", true, &["한", "국어 문장"]);
        // A three-syllable word cannot fit here: safe emergency wrapping.
        assert!(words.iter().any(|(_, column)| *column > 0));
        assert_eq!(syllables.len(), words.len());
        assert!(layout
            .cells
            .iter()
            .all(|cell| cell.minimum_oikomi_extent <= cell.extent));
    }

    #[test]
    fn korean_word_boundaries_cross_styles_and_graphemes_stay_together() {
        let mut paragraph = vertical_paragraph(
            vec![make_span("한글 "), make_span("한"), make_span("국어")],
            TextAlign::Left,
            1.0,
        );
        paragraph.set_language("ko".to_string());
        paragraph.set_korean_wrap(crate::shapes::text_policy::KoreanWrap::SpacesOnly);
        let content = content_of(vec![paragraph], EM * 3.6, GrowType::Fixed);
        let layout = layout_with_height(
            &provider(include_bytes!("../../fonts/cjk-locl-test.ttf")),
            &content,
            EM * 3.6,
        );
        let word: Vec<_> = layout.cells.iter().filter(|cell| cell.start >= 3).collect();
        assert_eq!(word.len(), 3);
        assert!(word.iter().all(|cell| cell.column == word[0].column));
        assert!(word[0].column > 0);

        let mut paragraph =
            vertical_paragraph(vec![make_span("ᄒ"), make_span("ᅡᆫ국")], TextAlign::Left, 1.0);
        paragraph.set_language("ko".to_string());
        let content = content_of(vec![paragraph], EM, GrowType::Fixed);
        let layout = layout_with_height(
            &provider(include_bytes!("../../fonts/cjk-locl-test.ttf")),
            &content,
            EM,
        );
        let syllable: Vec<_> = layout.cells.iter().filter(|cell| cell.start < 3).collect();
        assert!(syllable
            .iter()
            .all(|cell| cell.column == syllable[0].column));
    }

    fn with_headless_fonts<T>(test: impl FnOnce() -> T) -> T {
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        test()
    }

    #[test]
    fn vertical_layout_is_cached_until_the_content_changes() {
        with_headless_fonts(|| {
            let mut content = make_content(&["あいう"], 100.0);
            let rect = Rect::from_xywh(0.0, 0.0, 60.0, 100.0);
            let first = content.vertical_layout(&rect);

            assert!(std::rc::Rc::ptr_eq(&first, &content.vertical_layout(&rect)));
            assert!(
                std::rc::Rc::ptr_eq(&first, &content.clone().vertical_layout(&rect)),
                "clones share the cached layout"
            );
            let taller = Rect::from_xywh(0.0, 0.0, 60.0, 200.0);
            assert!(!std::rc::Rc::ptr_eq(
                &first,
                &content.vertical_layout(&taller)
            ));

            content.paragraphs_mut()[0].children_mut()[0].text = "えお".to_string();
            let edited = content.vertical_layout(&rect);
            assert!(!std::rc::Rc::ptr_eq(&first, &edited));
            assert_eq!(edited.cells.len(), 2, "an edit lays out again");
        });
    }

    #[test]
    fn vertical_layout_wraps_at_the_given_box_not_the_stored_bounds() {
        with_headless_fonts(|| {
            // Stored bounds are taller than the shape, as after measuring a fixed text.
            let content = make_content(&["あいうえ"], 1000.0);
            let wide = content.vertical_layout(&Rect::from_xywh(0.0, 0.0, 60.0, 1000.0));
            let height = wide.cells[0].extent * 2.0 + 1.0;
            let layout = content.vertical_layout(&Rect::from_xywh(0.0, 0.0, 60.0, height));
            assert_ne!(layout.cells[0].column, layout.cells[3].column);
        });
    }

    #[test]
    fn update_layout_of_vertical_text_skips_the_horizontal_layout() {
        with_headless_fonts(|| {
            let mut content = make_content(&["あいう"], 100.0);
            content.update_layout(Rect::from_xywh(0.0, 0.0, 60.0, 100.0));
            assert!(!content.needs_update_layout());
            assert!(content.layout.paragraphs.iter().all(Vec::is_empty));
        });
    }

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
    fn warichu_span_with_a_reading_breaks_and_shows_no_ruby() {
        let budget = 6.0 * EM;
        let note = TextSpan {
            ruby: "よみ".to_string(),
            ..warichu_span("あくあくあくあくあく")
        };
        let content = spans_content(vec![make_span("くくくく"), note], budget);
        let layout = layout_with_height(&provider(VMTX_TEST_FONT), &content, budget);
        assert_eq!(warichu_pieces(&layout).len(), 2, "the note still breaks");
        assert!(layout.ruby_cells.is_empty(), "warichu shows no ruby");
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
