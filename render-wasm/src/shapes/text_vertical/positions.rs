use std::ops::Range;

use skia_safe::{Contains, Point as SkPoint};

use crate::math::Rect;
use crate::shapes::{PositionData, VerticalAlign};

use super::annotations::{emphasis_mark_center, ruby_strip_x};
use super::layout::{column_base_center, CellKind, VerticalCell, VerticalLayout};

/// Position-data `direction` of a vertical-rl strip; 0/1 are rtl/ltr.
pub const DIRECTION_VERTICAL_RL: u32 = 2;

/// Position-data `direction` of a ruby strip; offsets index the *ruby* string.
pub const DIRECTION_VERTICAL_RUBY: u32 = 3;

/// Position-data `direction` of one emphasis mark (圏点) box, in any mode.
pub const DIRECTION_EMPHASIS_MARK: u32 = 4;

/// Paragraph source UTF-16 range of a `transformed` span-text range.
fn source_utf16_range(
    layout: &VerticalLayout,
    paragraph: usize,
    span: usize,
    transformed: Range<usize>,
) -> Range<usize> {
    let transformed_span_start = layout.span_utf16_starts[paragraph][span];
    let source_span_start = layout.span_source_utf16_starts[paragraph][span];
    let relative = layout.span_transforms[paragraph][span].source_utf16_range(
        transformed.start - transformed_span_start..transformed.end - transformed_span_start,
    );
    source_span_start + relative.start..source_span_start + relative.end
}

pub(super) fn cell_source_utf16_range(
    layout: &VerticalLayout,
    cell: &VerticalCell,
) -> Range<usize> {
    source_utf16_range(layout, cell.paragraph, cell.span, cell.start..cell.end)
}

/// Entry of `cell`'s span covering the paragraph source range `source`.
fn span_entry(
    layout: &VerticalLayout,
    cell: &VerticalCell,
    source: Range<usize>,
    (x, y, width, height): (f32, f32, f32, f32),
    direction: u32,
) -> PositionData {
    let span_start = layout.span_source_utf16_starts[cell.paragraph][cell.span];
    PositionData {
        paragraph: cell.paragraph as u32,
        span: cell.span as u32,
        start_pos: (source.start - span_start) as u32,
        end_pos: (source.end - span_start) as u32,
        x,
        y,
        width,
        height,
        direction,
    }
}

/// The two sub-line strips of a warichu cell: the first on the right half of
/// the base band, the second on the left, each holding its own characters.
fn warichu_entries(
    layout: &VerticalLayout,
    cell: &VerticalCell,
    first_chars: usize,
    origin: (f32, f32),
) -> [PositionData; 2] {
    let column = &layout.columns[cell.column];
    let center = origin.0 + column_base_center(column);
    let half = cell.font_size / 2.0;
    let split = cell.start + first_chars;
    let line = |range: Range<usize>, x: f32| {
        let source = source_utf16_range(layout, cell.paragraph, cell.span, range);
        let rect = (x, origin.1 + cell.top, half, cell.extent);
        span_entry(layout, cell, source, rect, DIRECTION_VERTICAL_RL)
    };
    [
        line(cell.start..split, center),
        line(split..cell.end, center - half),
    ]
}

/// Position-data entries for the v2 editor / exports: consecutive cells of
/// the same span in the same column merge into one vertical strip.
pub fn position_data(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
) -> Vec<PositionData> {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    let mut result: Vec<PositionData> = Vec::new();

    let mut i = 0;
    while i < layout.cells.len() {
        let first = &layout.cells[i];
        if let CellKind::Warichu { first_chars, .. } = first.kind {
            result.extend(warichu_entries(
                layout,
                first,
                first_chars,
                (origin_x, origin_y),
            ));
            i += 1;
            continue;
        }
        let mut source_range = cell_source_utf16_range(layout, first);
        let mut bottom = first.top + first.extent;
        let mut j = i + 1;
        while j < layout.cells.len() {
            let next = &layout.cells[j];
            if next.paragraph == first.paragraph
                && next.span == first.span
                && next.column == first.column
                && !matches!(next.kind, CellKind::Warichu { .. })
            {
                let next_source = cell_source_utf16_range(layout, next);
                source_range.start = source_range.start.min(next_source.start);
                source_range.end = source_range.end.max(next_source.end);
                bottom = next.top + next.extent;
                j += 1;
            } else {
                break;
            }
        }
        let column = &layout.columns[first.column];
        // Base sub-band only, so overlay and selection skip the ruby gutter.
        let rect = (
            origin_x + column.x,
            origin_y + first.top,
            column.base_width,
            bottom - first.top,
        );
        result.push(span_entry(
            layout,
            first,
            source_range,
            rect,
            DIRECTION_VERTICAL_RL,
        ));
        i = j;
    }

    // Ruby strips at the flow positions the canvas paints. `start_pos` and
    // `end_pos` are UTF-16 offsets into the span's ruby string, taken from the
    // shaped clusters so surrogate pairs slice correctly.
    for ruby in &layout.ruby_cells {
        let (Some(first), Some(last)) = (ruby.glyphs.first(), ruby.glyphs.last()) else {
            continue;
        };
        if ruby.base_segments.is_empty() {
            continue;
        }
        let column = &layout.columns[ruby.column];
        let top = ruby.glyph_tops.iter().copied().fold(f32::MAX, f32::min);
        let bottom = ruby.glyph_tops.iter().copied().fold(f32::MIN, f32::max) + ruby.font_size;
        result.push(PositionData {
            paragraph: ruby.paragraph as u32,
            span: ruby.span as u32,
            start_pos: first.utf16_start as u32,
            end_pos: last.utf16_end as u32,
            x: origin_x + ruby_strip_x(column, ruby.font_size, ruby.base_font_size, ruby.side),
            y: origin_y + top,
            width: ruby.font_size,
            height: bottom - top,
            direction: DIRECTION_VERTICAL_RUBY,
        });
    }

    for mark in &layout.emphasis_marks {
        let cell = &layout.cells[mark.cell];
        let (center_x, center_y) = emphasis_mark_center(layout, mark);
        let size = mark.font_size;
        let rect = (
            origin_x + center_x - size / 2.0,
            origin_y + center_y - size / 2.0,
            size,
            size,
        );
        let source = cell_source_utf16_range(layout, cell);
        result.push(span_entry(
            layout,
            cell,
            source,
            rect,
            DIRECTION_EMPHASIS_MARK,
        ));
    }
    result
}

/// True when the point (in the same space as `bounds`) hits laid-out text.
pub fn intersects(
    layout: &VerticalLayout,
    bounds: &Rect,
    vertical_align: VerticalAlign,
    x: f32,
    y: f32,
) -> bool {
    let (origin_x, origin_y) = layout.origin(bounds, vertical_align);
    layout.cells.iter().any(|cell| {
        let column = &layout.columns[cell.column];
        let rect = Rect::from_xywh(
            origin_x + column.x,
            origin_y + cell.top,
            column.width,
            cell.extent,
        );
        rect.contains(&SkPoint::new(x, y))
    })
}

fn scalar_offset_from_utf16(
    layout: &VerticalLayout,
    paragraph: usize,
    utf16_offset: usize,
) -> Option<usize> {
    let boundaries = layout.paragraph_utf16_boundaries.get(paragraph)?;
    Some(match boundaries.binary_search(&utf16_offset) {
        Ok(index) => index,
        Err(index) => index.saturating_sub(1),
    })
}

fn cell_scalar_range(layout: &VerticalLayout, cell: &VerticalCell) -> Option<(usize, usize)> {
    Some((
        scalar_offset_from_utf16(layout, cell.paragraph, cell.start)?,
        scalar_offset_from_utf16(layout, cell.paragraph, cell.end)?,
    ))
}

/// Scalar length of a warichu cell's first sub-line. `first_chars` is its
/// UTF-16 length; the cell holds `chars` scalars from `cell_start`.
fn warichu_first_line_len(
    layout: &VerticalLayout,
    cell: &VerticalCell,
    first_chars: usize,
    cell_start: usize,
    chars: usize,
) -> Option<usize> {
    let first_end = scalar_offset_from_utf16(layout, cell.paragraph, cell.start + first_chars)?;
    Some(first_end.saturating_sub(cell_start).min(chars))
}

/// Caret position (paragraph index, paragraph-relative Unicode scalar
/// offset) for a point relative to the content block's top-left origin.
pub fn caret_from_point(layout: &VerticalLayout, x: f32, y: f32) -> Option<(usize, usize)> {
    if layout.columns.is_empty() {
        return None;
    }

    // Columns are ordered right->left, i.e. descending x.
    let column_index = layout
        .columns
        .iter()
        .position(|c| x >= c.x && x < c.x + c.width)
        .unwrap_or(if x >= layout.width {
            0
        } else {
            layout.columns.len() - 1
        });

    let paragraph = layout
        .paragraph_columns
        .iter()
        .position(|(start, end)| column_index >= *start && column_index < *end)?;

    let column_cells: Vec<&VerticalCell> = layout
        .cells
        .iter()
        .filter(|c| c.column == column_index)
        .collect();

    if column_cells.is_empty() {
        return Some((paragraph, 0));
    }

    for cell in &column_cells {
        if y < cell.top + cell.extent {
            let (cell_start, cell_end) = cell_scalar_range(layout, cell)?;
            let chars = (cell_end - cell_start).max(1);
            let offset = match cell.kind {
                CellKind::Rotated { .. } => {
                    // Proportional position along the rotated run.
                    let frac = ((y - cell.top) / cell.extent).clamp(0.0, 1.0);
                    cell_start + ((frac * chars as f32).round() as usize).min(chars)
                }
                CellKind::TateChuYoko { .. } => {
                    // The digits run left->right inside the composite, so the
                    // horizontal position picks the offset within it.
                    let column = &layout.columns[cell.column];
                    let left = column_base_center(column) - cell.h_advance / 2.0;
                    let frac = ((x - left) / cell.h_advance.max(1.0)).clamp(0.0, 1.0);
                    cell_start + ((frac * chars as f32).round() as usize).min(chars)
                }
                CellKind::Warichu { first_chars, .. } => {
                    // The right sub-column holds the first sub-line, the left
                    // the second; y picks the offset within that sub-line.
                    let column = &layout.columns[cell.column];
                    let centre = column_base_center(column);
                    let first =
                        warichu_first_line_len(layout, cell, first_chars, cell_start, chars)?;
                    let (lo, hi) = if x >= centre {
                        (0, first)
                    } else {
                        (first, chars)
                    };
                    let line_chars = (hi - lo).max(1);
                    let frac = ((y - cell.top) / cell.extent.max(1.0)).clamp(0.0, 1.0);
                    cell_start + lo + ((frac * line_chars as f32).round() as usize).min(hi - lo)
                }
                CellKind::Upright { .. } | CellKind::SyntheticRotated { .. } => {
                    if y < cell.top + cell.extent / 2.0 {
                        cell_start
                    } else {
                        cell_end
                    }
                }
            };
            return Some((paragraph, offset));
        }
    }

    Some((
        paragraph,
        scalar_offset_from_utf16(layout, paragraph, column_cells.last().unwrap().end)?,
    ))
}

/// Caret rect for a paragraph-relative Unicode scalar offset, in
/// content-local coordinates. It spans the column width; its height is the
/// extent of the character at the offset (overtype carets cover the glyph),
/// or zero after the last character.
pub fn caret_rect(layout: &VerticalLayout, paragraph: usize, offset: usize) -> Option<Rect> {
    let (col_start, _) = *layout.paragraph_columns.get(paragraph)?;

    let mut result: Option<Rect> = None;
    for cell in layout.cells.iter().filter(|c| c.paragraph == paragraph) {
        let (cell_start, cell_end) = cell_scalar_range(layout, cell)?;
        if cell_start > offset {
            continue;
        }
        let column = &layout.columns[cell.column];
        let chars = (cell_end - cell_start).max(1);
        let rect = if offset >= cell_end {
            Rect::from_xywh(column.x, cell.top + cell.extent, column.base_width, 0.0)
        } else {
            match cell.kind {
                CellKind::TateChuYoko { .. } => {
                    // Digits run left->right inside the composite: the offset
                    // moves the caret along the horizontal axis while the
                    // rect keeps the composite's flow extent.
                    let left = column_base_center(column) - cell.h_advance / 2.0;
                    let width = cell.h_advance / chars as f32;
                    let x = left + (offset - cell_start) as f32 * width;
                    Rect::from_xywh(x, cell.top, width, cell.extent)
                }
                CellKind::Warichu { first_chars, .. } => {
                    // The right sub-column holds the first sub-line, the
                    // left the second (jlreq reading order); the caret moves
                    // down that half-width sub-line.
                    let first =
                        warichu_first_line_len(layout, cell, first_chars, cell_start, chars)?;
                    let within = offset - cell_start;
                    let centre = column_base_center(column);
                    let half = cell.font_size / 2.0;
                    let (band_x, line_start, line_chars) = if within < first {
                        (centre, 0, first)
                    } else {
                        (centre - half, first, chars - first)
                    };
                    let line_chars = line_chars.max(1);
                    let frac = (within - line_start) as f32 / line_chars as f32;
                    Rect::from_xywh(
                        band_x,
                        cell.top + frac * cell.extent,
                        half,
                        cell.extent / line_chars as f32,
                    )
                }
                _ => {
                    let frac = (offset - cell_start) as f32 / chars as f32;
                    Rect::from_xywh(
                        column.x,
                        cell.top + frac * cell.extent,
                        column.base_width,
                        cell.extent / chars as f32,
                    )
                }
            }
        };
        result = Some(rect);
        if offset < cell_end {
            break;
        }
    }

    result.or_else(|| {
        // Empty paragraph: caret at the top of its (empty) first column.
        layout
            .columns
            .get(col_start)
            .map(|column| Rect::from_xywh(column.x, 0.0, column.base_width, 0.0))
    })
}

/// Selection rects for a paragraph-relative Unicode scalar offset range, in
/// content-local coordinates.
pub fn range_rects(
    layout: &VerticalLayout,
    paragraph: usize,
    start: usize,
    end: usize,
) -> Vec<Rect> {
    let mut rects: Vec<Rect> = Vec::new();
    for cell in layout.cells.iter().filter(|c| c.paragraph == paragraph) {
        let Some((cell_start, cell_end)) = cell_scalar_range(layout, cell) else {
            continue;
        };
        if cell_end <= start || cell_start >= end {
            continue;
        }
        let column = &layout.columns[cell.column];
        let chars = (cell_end - cell_start).max(1);
        let sel_top = if start > cell_start {
            cell.top + ((start - cell_start) as f32 / chars as f32) * cell.extent
        } else {
            cell.top
        };
        let sel_bottom = if end < cell_end {
            cell.top + ((end - cell_start) as f32 / chars as f32) * cell.extent
        } else {
            cell.top + cell.extent
        };
        if sel_bottom <= sel_top {
            continue;
        }
        // Merge with the previous rect when contiguous in the same column.
        if let Some(last) = rects.last_mut() {
            if (last.left - column.x).abs() < f32::EPSILON && (last.bottom - sel_top).abs() < 0.01 {
                last.bottom = sel_bottom;
                continue;
            }
        }
        rects.push(Rect::from_ltrb(
            column.x,
            sel_top,
            column.x + column.base_width,
            sel_bottom,
        ));
    }
    rects
}

#[cfg(test)]
mod tests {
    use super::super::test_support::*;
    use super::*;
    use crate::shapes::{
        TextCombineUpright, TextEmphasis, TextOrientation, TextPositionWithAffinity, TextSpan,
        TextTransform,
    };
    use crate::wasm::text::helpers as text_helpers;

    #[test]
    fn caret_from_point_lands_inside_tcy_composite() {
        let mut content = make_content_with_spans(&["1234", "あ"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_content(&content, 400.0);
        let cell = &layout.cells[0];
        assert!(matches!(cell.kind, CellKind::TateChuYoko { .. }));
        let column = &layout.columns[cell.column];
        let y_mid = cell.top + cell.extent / 2.0;
        let (_, at_left) = caret_from_point(&layout, column.x + 0.5, y_mid).unwrap();
        let (_, at_right) =
            caret_from_point(&layout, column.x + column.base_width - 0.5, y_mid).unwrap();
        assert!(at_left <= 1, "left edge maps near the start, got {at_left}");
        assert!(
            at_right >= 3,
            "right edge maps near the end, got {at_right}"
        );
    }

    #[test]
    fn caret_rect_tracks_horizontal_axis_inside_tcy_composite() {
        let mut content = make_content_with_spans(&["1234", "あ"], 400.0);
        content.paragraphs_mut()[0].children_mut()[0]
            .set_text_combine_upright(TextCombineUpright::All);
        let layout = layout_content(&content, 400.0);
        let cell = &layout.cells[0];
        assert!(matches!(cell.kind, CellKind::TateChuYoko { .. }));
        let left = column_base_center(&layout.columns[cell.column]) - cell.h_advance / 2.0;
        let digit = cell.h_advance / 4.0;
        for i in 0..4 {
            let rect = caret_rect(&layout, 0, cell.start + i).expect("caret rect");
            assert!(
                (rect.left - (left + i as f32 * digit)).abs() < 0.01,
                "digit {i} caret sits at its horizontal slot, got {}",
                rect.left
            );
            assert!(
                (rect.width() - digit).abs() < 0.01,
                "digit {i} caret is one digit wide, got {}",
                rect.width()
            );
            assert!(
                (rect.top - cell.top).abs() < 0.01 && (rect.height() - cell.extent).abs() < 0.01,
                "digit {i} caret spans the composite's flow extent"
            );
        }
        // After the composite the caret falls back to the flow axis.
        let rect = caret_rect(&layout, 0, cell.end).expect("caret rect");
        assert!(rect.height() < 0.01 || rect.top >= cell.top + cell.extent - 0.01);
    }

    #[test]
    fn position_data_emits_one_strip_per_warichu_sub_line() {
        let content = warichu_content("あいう、えお", 400.0);
        let layout = layout_content(&content, 400.0);
        let data = position_data(&layout, &content.bounds(), VerticalAlign::Top);

        let strips: Vec<&PositionData> = data
            .iter()
            .filter(|entry| entry.direction == DIRECTION_VERTICAL_RL)
            .collect();
        assert_eq!(strips.len(), 2);
        assert_eq!((strips[0].start_pos, strips[0].end_pos), (0, 4));
        assert_eq!((strips[1].start_pos, strips[1].end_pos), (4, 6));
        assert!(
            strips[0].x > strips[1].x,
            "the first sub-line reads first, on the right"
        );
        assert!((strips[0].width - EM / 2.0).abs() < 0.01);
    }

    #[test]
    fn position_data_emits_each_emphasis_mark_box() {
        let content = spans_content(
            vec![TextSpan {
                text_emphasis: TextEmphasis::FilledDot,
                text_orientation: TextOrientation::Upright,
                ..make_span("A、B")
            }],
            400.0,
        );
        let layout = layout_content(&content, 400.0);
        let data = position_data(&layout, &content.bounds(), VerticalAlign::Top);

        let marks: Vec<&PositionData> = data
            .iter()
            .filter(|entry| entry.direction == DIRECTION_EMPHASIS_MARK)
            .collect();
        let ranges: Vec<(u32, u32)> = marks.iter().map(|m| (m.start_pos, m.end_pos)).collect();
        assert_eq!(
            ranges,
            vec![(0, 1), (2, 3)],
            "A and B are marked; 、 is not"
        );
        let base = data
            .iter()
            .find(|entry| entry.direction == DIRECTION_VERTICAL_RL)
            .expect("base strip");
        for mark in marks {
            assert!((mark.width - EM / 2.0).abs() < 0.01);
            assert!(
                mark.x >= base.x + base.width - 0.01,
                "marks sit in the gutter right of the base band"
            );
        }
    }

    #[test]
    fn warichu_cell_carries_kinsoku_split() {
        let content = warichu_content("あいう、えお", 400.0);
        let layout = layout_content(&content, 400.0);
        let cell = layout
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Warichu { .. }))
            .expect("a warichu cell");
        let CellKind::Warichu { first_chars, .. } = cell.kind else {
            unreachable!();
        };
        assert_eq!(
            first_chars, 4,
            "the comma is pulled up into the first sub-line"
        );
        // The caret for the second sub-line's first character restarts at
        // the composite top, left of the axis.
        let column = &layout.columns[cell.column];
        let centre = column_base_center(column);
        let rect = caret_rect(&layout, 0, cell.start + 4).expect("caret rect");
        assert!((rect.top - cell.top).abs() < 0.01);
        assert!(rect.left < centre);
    }

    #[test]
    fn caret_rect_tracks_sub_lines_inside_warichu() {
        let content = warichu_content("割注二行説明", 400.0);
        let layout = layout_content(&content, 400.0);
        let cell = layout
            .cells
            .iter()
            .find(|c| matches!(c.kind, CellKind::Warichu { .. }))
            .expect("a warichu cell");
        let column = &layout.columns[cell.column];
        let centre = column_base_center(column);
        let half = cell.font_size / 2.0;
        // First sub-line (offsets 0..3) sits right of the column axis.
        let first = caret_rect(&layout, 0, cell.start).expect("caret rect");
        assert!(
            (first.left - centre).abs() < 0.01,
            "first sub-line caret starts at the axis, got {}",
            first.left
        );
        assert!(
            (first.width() - half).abs() < 0.01,
            "sub-line caret is half-size wide, got {}",
            first.width()
        );
        assert!((first.top - cell.top).abs() < 0.01);
        // Second sub-line (offsets 3..6) sits left of the axis, restarting
        // at the composite top.
        let second = caret_rect(&layout, 0, cell.start + 3).expect("caret rect");
        assert!(
            (second.left - (centre - half)).abs() < 0.01,
            "second sub-line caret sits left of the axis, got {}",
            second.left
        );
        assert!(
            (second.top - cell.top).abs() < 0.01,
            "second sub-line restarts at the composite top, got {}",
            second.top
        );
        let deeper = caret_rect(&layout, 0, cell.start + 4).expect("caret rect");
        assert!(
            deeper.top > second.top,
            "later offsets move down the sub-line"
        );
    }

    #[test]
    fn caret_round_trip() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        for cell in &layout.cells {
            let column = &layout.columns[cell.column];
            // A point in the upper half of the cell resolves to its start.
            let (paragraph, offset) = caret_from_point(
                &layout,
                column.x + column.width / 2.0,
                cell.top + cell.extent * 0.25,
            )
            .expect("caret");
            assert_eq!(paragraph, 0);
            assert_eq!(offset, cell.start);

            // caret_rect for that offset lands inside the same column and
            // carries the character extent (for overtype carets).
            let rect = caret_rect(&layout, paragraph, offset).expect("caret rect");
            assert!((rect.x() - column.x).abs() < 0.01);
            let chars = (cell.end - cell.start).max(1);
            assert!((rect.height() - cell.extent / chars as f32).abs() < 0.01);
        }

        // After the last character there is no glyph to cover: zero height.
        let end_offset = layout.cells.last().unwrap().end;
        let rect = caret_rect(&layout, 0, end_offset).expect("caret rect");
        assert_eq!(rect.height(), 0.0);
    }

    #[test]
    fn non_bmp_caret_offsets_round_trip_through_editor_operations() {
        let content = make_content(&["𠀀あ"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let first = &layout.cells[0];
        let second = &layout.cells[1];
        let column = &layout.columns[first.column];
        let x = column_base_center(column);

        let before = caret_from_point(&layout, x, first.top + first.extent * 0.25).unwrap();
        let after = caret_from_point(&layout, x, first.top + first.extent * 0.75).unwrap();
        assert_eq!(before, (0, 0));
        assert_eq!(after, (0, 1));

        let rect = caret_rect(&layout, after.0, after.1).expect("caret rect");
        assert!((rect.top - second.top).abs() < 0.01);
        assert!((rect.height() - second.extent).abs() < 0.01);

        let rects = range_rects(&layout, 0, 0, 1);
        assert_eq!(rects.len(), 1);
        assert!((rects[0].height() - first.extent).abs() < 0.01);

        let start = TextPositionWithAffinity::new_downstream_affinity(0, 0);
        let end = TextPositionWithAffinity::new_downstream_affinity(after.0, after.1);
        assert_eq!(
            text_helpers::move_cursor_forward(&start, content.paragraphs(), false),
            end
        );
        assert_eq!(
            text_helpers::move_cursor_backward(&end, content.paragraphs(), false),
            start
        );

        let mut inserted = make_content(&["𠀀あ"], 1000.0);
        assert_eq!(
            text_helpers::insert_text_at_cursor(&mut inserted, &end, "X"),
            Some(2)
        );
        assert_eq!(inserted.paragraphs()[0].children()[0].text, "𠀀Xあ");

        let mut deleted = make_content(&["𠀀あ"], 1000.0);
        assert_eq!(
            text_helpers::delete_char_before(&mut deleted, &end),
            Some(start)
        );
        assert_eq!(deleted.paragraphs()[0].children()[0].text, "あ");
    }

    #[test]
    fn selection_rects_cover_the_range() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);

        let rects = range_rects(&layout, 0, 1, 3);
        assert!(!rects.is_empty());
        let covered: f32 = rects.iter().map(|r| r.height()).sum();
        let expected: f32 = layout
            .cells
            .iter()
            .filter(|c| c.start >= 1 && c.end <= 3)
            .map(|c| c.extent)
            .sum();
        assert!((covered - expected).abs() < 0.01);
    }

    #[test]
    fn position_data_merges_by_column_and_maps_span_offsets() {
        let text = "あいうえお";
        let content = make_content(&[text], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();

        let data = position_data(&layout, &bounds, VerticalAlign::Top);
        assert_eq!(data.len(), 1, "one column, one span => one entry");
        assert_eq!(data[0].start_pos, 0);
        assert_eq!(data[0].end_pos, text.encode_utf16().count() as u32);
        // Right-anchored: the strip ends at the bounds right edge.
        assert!((data[0].x + data[0].width - bounds.right).abs() < 0.01);
    }

    #[test]
    fn position_data_keeps_expanded_transform_in_one_source_safe_strip() {
        let mut content = make_content(&["AßB"], 1000.0);
        let span = &mut content.paragraphs_mut()[0].children_mut()[0];
        span.text_transform = Some(TextTransform::Uppercase);
        span.text_orientation = TextOrientation::Upright;
        let unwrapped = layout_content(&content, 1000.0);
        let expanded_extent: f32 = unwrapped
            .cells
            .iter()
            .filter(|cell| cell_source_utf16_range(&unwrapped, cell) == (1..2))
            .map(|cell| cell.extent)
            .sum();
        let layout = layout_content(&content, expanded_extent + 0.01);

        let expanded: Vec<&VerticalCell> = layout
            .cells
            .iter()
            .filter(|cell| cell_source_utf16_range(&layout, cell) == (1..2))
            .collect();
        assert_eq!(expanded.len(), 2, "ß must shape as the two cells in SS");
        assert_eq!(
            expanded[0].column, expanded[1].column,
            "glyphs from one source character must not split across columns"
        );

        let data = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        let ranges: Vec<(u32, u32)> = data
            .iter()
            .filter(|entry| entry.direction == DIRECTION_VERTICAL_RL)
            .map(|entry| (entry.start_pos, entry.end_pos))
            .collect();
        assert_eq!(ranges, vec![(0, 1), (1, 2), (2, 3)]);
    }

    #[test]
    fn position_data_emits_ruby_annotation_strips() {
        let content = ruby_content("漢字", "かんじ", 400.0);
        let layout = layout_content(&content, 400.0);
        let bounds = content.bounds();
        let data = position_data(&layout, &bounds, VerticalAlign::Top);

        let base: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RL)
            .collect();
        let ruby: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RUBY)
            .collect();
        assert_eq!(base.len(), 1);
        assert_eq!(ruby.len(), 1, "one annotated column => one ruby strip");
        assert_eq!(ruby[0].start_pos, 0);
        assert_eq!(ruby[0].end_pos, 3, "offsets index the ruby string");
        // The strip sits in the gutter, to the right of the base band.
        assert!(ruby[0].x >= base[0].x + base[0].width - 0.001);
        assert!(ruby[0].width > 0.0);
        assert!(ruby[0].height > 0.0);
    }

    #[test]
    fn position_data_ruby_offsets_are_utf16_for_non_bmp_readings() {
        // Two surrogate-pair reading characters: 2 glyphs but 4 UTF-16
        // units. The strip offsets must slice the ruby string by UTF-16.
        let content = ruby_content("\u{6f22}\u{5b57}", "\u{1d4aa}\u{1d4ab}", 400.0);
        let layout = layout_content(&content, 400.0);
        let bounds = content.bounds();
        let data = position_data(&layout, &bounds, VerticalAlign::Top);
        let ruby: Vec<&PositionData> = data
            .iter()
            .filter(|d| d.direction == DIRECTION_VERTICAL_RUBY)
            .collect();
        assert_eq!(ruby.len(), 1);
        assert_eq!(ruby[0].start_pos, 0);
        assert_eq!(
            ruby[0].end_pos, 4,
            "surrogate pairs count two UTF-16 units each"
        );
    }

    #[test]
    fn position_data_follows_block_axis_alignment() {
        let content = make_content(&["あいう"], 1000.0);
        let layout = layout_content(&content, 1000.0);
        let bounds = content.bounds();
        let top = position_data(&layout, &bounds, VerticalAlign::Top);
        let center = position_data(&layout, &bounds, VerticalAlign::Center);
        let bottom = position_data(&layout, &bounds, VerticalAlign::Bottom);

        assert!(top[0].x > center[0].x);
        assert!(center[0].x > bottom[0].x);
        assert!((bottom[0].x - bounds.left).abs() < 0.01);
    }

    #[test]
    fn ruby_selection_geometry_excludes_gutter() {
        let content = ruby_content("AB", "ab", 400.0);
        let layout = layout_content(&content, 400.0);
        let base_width = layout.columns[0].base_width;
        assert!(base_width < layout.columns[0].width);

        let pd = position_data(&layout, &content.bounds(), VerticalAlign::Top);
        assert!(!pd.is_empty());
        for entry in pd.iter().filter(|e| e.direction == DIRECTION_VERTICAL_RL) {
            assert!(
                (entry.width - base_width).abs() < 0.01,
                "position-data strip must be the base band, not the full column"
            );
        }

        let rects = range_rects(&layout, 0, 0, 10);
        assert!(!rects.is_empty());
        for rect in &rects {
            assert!(
                (rect.width() - base_width).abs() < 0.01,
                "selection rect must be the base band, not the full column"
            );
        }
    }
}
