use super::*;
use crate::shapes::text_paragraph::LayoutParagraph;

pub(super) const HORIZONTAL_WARICHU_BUILDER_LEN: usize = 3;
pub(super) const HORIZONTAL_WARICHU_STYLE_ANCHOR: char = '\u{00A0}';
pub(super) const HORIZONTAL_WARICHU_BREAK_ANCHOR: char = '\u{200B}';

/// Piece of a warichu note laid out as one box: its characters in the
/// transformed span text, and their UTF-16 range in that text. A note too
/// long for its line continues in pieces on the lines after it.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct WarichuPiece {
    pub chars: std::ops::Range<usize>,
    pub utf16: std::ops::Range<usize>,
}

/// Pieces of the warichu `text` cut at the char offsets `splits`.
pub(crate) fn warichu_pieces(text: &str, splits: &[usize]) -> Vec<WarichuPiece> {
    let mut pieces = Vec::new();
    let (mut chars, mut utf16) = (0, 0);
    let (mut start_char, mut start_utf16) = (0, 0);
    for ch in text.chars() {
        if chars > start_char && splits.contains(&chars) {
            pieces.push(WarichuPiece {
                chars: start_char..chars,
                utf16: start_utf16..utf16,
            });
            (start_char, start_utf16) = (chars, utf16);
        }
        chars += 1;
        utf16 += ch.len_utf16();
    }
    pieces.push(WarichuPiece {
        chars: start_char..chars,
        utf16: start_utf16..utf16,
    });
    pieces
}

/// Text of a warichu `piece` of the transformed span `text`.
fn piece_text(text: &str, piece: &WarichuPiece) -> String {
    text.chars()
        .skip(piece.chars.start)
        .take(piece.chars.len())
        .collect()
}

/// Placeholder rect of each horizontal warichu piece, keyed by span and
/// piece index. Tabs in normal spans are placeholders too, so they are
/// skipped.
pub(crate) fn horizontal_warichu_placeholders(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
) -> Vec<(usize, usize, skia::Rect)> {
    let mut placeholders = laid_out.get_rects_for_placeholders().into_iter();
    let mut result = Vec::new();
    for (index, span) in paragraph.children().iter().enumerate() {
        if span.is_warichu() {
            let pieces = offsets
                .ranges
                .get(index)
                .map_or(1, |range| range.pieces.len().max(1));
            for piece in 0..pieces {
                if let Some(textbox) = placeholders.next() {
                    result.push((index, piece, textbox.rect));
                }
            }
        } else {
            for _ in span.text.matches('\t') {
                placeholders.next();
            }
        }
    }
    result
}

/// One laid-out warichu piece.
struct WarichuBox {
    /// Paragraph source character offsets of the piece.
    source: std::ops::Range<usize>,
    /// Transformed text of the piece.
    text: String,
    rect: skia::Rect,
}

fn horizontal_warichu_boxes(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
) -> Vec<WarichuBox> {
    let mut span_starts = Vec::with_capacity(paragraph.children().len());
    let mut source_start = 0usize;
    for span in paragraph.children() {
        span_starts.push(source_start);
        source_start += span.text.chars().count();
    }
    horizontal_warichu_placeholders(paragraph, offsets, laid_out)
        .into_iter()
        .filter_map(|(index, piece, rect)| {
            let span = paragraph.children().get(index)?;
            let text = span.apply_text_transform();
            let length = span.text.chars().count();
            let whole = WarichuPiece {
                chars: 0..text.chars().count(),
                utf16: 0..text.encode_utf16().count(),
            };
            let piece = offsets
                .ranges
                .get(index)
                .and_then(|range| range.pieces.get(piece))
                .unwrap_or(&whole);
            let start = span_starts[index];
            Some(WarichuBox {
                source: start + piece.chars.start.min(length)..start + piece.chars.end.min(length),
                text: piece_text(&text, piece),
                rect,
            })
        })
        .collect()
}

/// The warichu piece holding `source_offset`: at the edge two pieces meet,
/// the earlier one when `upstream`, else the later one.
fn box_at(boxes: &[WarichuBox], source_offset: usize, upstream: bool) -> Option<&WarichuBox> {
    let mut holding = boxes
        .iter()
        .filter(|piece| piece.source.contains(&source_offset) || piece.source.end == source_offset);
    if upstream {
        holding.next()
    } else {
        holding.next_back()
    }
}

/// Vertical extent (top, bottom) of the laid-out line that holds `rect`.
fn line_band(laid_out: &LayoutParagraph, rect: &skia::Rect) -> (f32, f32) {
    laid_out
        .get_line_metrics()
        .iter()
        .map(|line| {
            (
                (line.baseline - line.ascent) as f32,
                (line.baseline + line.descent) as f32,
            )
        })
        .find(|(top, bottom)| rect.center_y() >= *top && rect.center_y() <= *bottom)
        .unwrap_or((rect.top(), rect.bottom()))
}

/// Source offset of the caret for a point on a warichu note, and whether it
/// is upstream: at the end of the first sub-line, not the start of the
/// second. A piece takes the points over its width across its whole line:
/// above it counts as the first sub-line, below it as the second.
pub(crate) fn horizontal_warichu_hit_test(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
    point: Point,
) -> Option<(usize, bool)> {
    for piece in horizontal_warichu_boxes(paragraph, offsets, laid_out) {
        let rect = piece.rect;
        let (band_top, band_bottom) = line_band(laid_out, &rect);
        if point.x < rect.left()
            || point.x >= rect.right()
            || point.y < band_top
            || point.y >= band_bottom
        {
            continue;
        }
        let split = warichu_split_chars(&piece.text);
        let total = piece.source.len();
        let second = point.y >= rect.top() + rect.height() / 2.0;
        let (line_start, line_len) = if second {
            (split, total - split)
        } else {
            (0, split)
        };
        let fraction = ((point.x - rect.left()) / rect.width().max(0.01)).clamp(0.0, 1.0);
        let within = ((fraction * line_len as f32).round() as usize).min(line_len);
        let upstream = !second && within == line_len && line_len < total;
        return Some((piece.source.start + line_start + within, upstream));
    }
    None
}

/// Caret rect at `source_offset` inside a warichu note, or None outside
/// every note. The offset where the sub-lines meet is drawn at the end of the
/// first sub-line when `upstream`, else at the start of the second.
pub(crate) fn horizontal_warichu_caret_rect(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
    source_offset: usize,
    upstream: bool,
) -> Option<skia::Rect> {
    let boxes = horizontal_warichu_boxes(paragraph, offsets, laid_out);
    let piece = box_at(&boxes, source_offset, upstream)?;
    let rect = piece.rect;
    let split = warichu_split_chars(&piece.text);
    let local = source_offset - piece.source.start;
    let total = piece.source.len();
    let second = local > split || (local == split && !upstream);
    let (line_start, line_len, top) = if second {
        (split, total - split, rect.top() + rect.height() / 2.0)
    } else {
        (0, split, rect.top())
    };
    let within = local.saturating_sub(line_start).min(line_len);
    let char_width = rect.width() / line_len.max(1) as f32;
    Some(skia::Rect::from_xywh(
        rect.left() + within as f32 * char_width,
        top,
        char_width,
        rect.height() / 2.0,
    ))
}

/// Caret moved to the other sub-line of the warichu piece holding
/// `source_offset`, keeping `x`: down from the first sub-line, up from the
/// second. None when the caret is outside every note or the move leaves it.
pub(crate) fn horizontal_warichu_step(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
    source_offset: usize,
    upstream: bool,
    x: f32,
    down: bool,
) -> Option<(usize, bool)> {
    let caret =
        horizontal_warichu_caret_rect(paragraph, offsets, laid_out, source_offset, upstream)?;
    let boxes = horizontal_warichu_boxes(paragraph, offsets, laid_out);
    let rect = box_at(&boxes, source_offset, upstream)?.rect;
    let second = caret.top() > rect.top() + rect.height() / 4.0;
    if down == second {
        return None;
    }
    let y = if down {
        rect.top() + rect.height() * 0.75
    } else {
        rect.top() + rect.height() * 0.25
    };
    let x = x.clamp(rect.left(), rect.right() - 0.01);
    horizontal_warichu_hit_test(paragraph, offsets, laid_out, Point::new(x, y))
}

pub(crate) fn horizontal_warichu_range_rects(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
    source_start: usize,
    source_end: usize,
) -> Vec<skia::Rect> {
    let mut rects = Vec::new();
    for piece in horizontal_warichu_boxes(paragraph, offsets, laid_out) {
        let selected_start = source_start.max(piece.source.start);
        let selected_end = source_end.min(piece.source.end);
        if selected_start >= selected_end {
            continue;
        }
        let rect = piece.rect;
        let split = warichu_split_chars(&piece.text);
        let piece_start = piece.source.start;
        for (line_start, line_end, top) in [
            (piece_start, piece_start + split, rect.top()),
            (
                piece_start + split,
                piece.source.end,
                rect.top() + rect.height() / 2.0,
            ),
        ] {
            let start = selected_start.max(line_start);
            let end = selected_end.min(line_end);
            if start >= end {
                continue;
            }
            let line_len = line_end - line_start;
            let char_width = rect.width() / line_len.max(1) as f32;
            rects.push(skia::Rect::from_xywh(
                rect.left() + (start - line_start) as f32 * char_width,
                top,
                (end - start) as f32 * char_width,
                rect.height() / 2.0,
            ));
        }
    }
    rects
}

/// The two warichu sub-lines of `text`, split at `warichu_split_chars`.
pub(crate) fn warichu_text_lines(text: &str) -> (&str, &str) {
    let split = warichu_split_chars(text);
    let split_byte = text
        .char_indices()
        .nth(split)
        .map(|(index, _)| index)
        .unwrap_or(text.len());
    text.split_at(split_byte)
}

/// Paint the two horizontal warichu sub-lines into SkParagraph's inline
/// placeholder boxes. Reading order is top line then bottom line.
pub(crate) fn paint_horizontal_warichu(
    canvas: &skia::Canvas,
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    laid_out: &LayoutParagraph,
    x: f32,
    y: f32,
) {
    visit_horizontal_warichu(paragraph, plan, laid_out, x, y, |line, origin| {
        line.paint(canvas, origin);
    });
}

/// Visit the two sub-lines of each horizontal warichu note as laid-out mini
/// paragraphs with the origins they paint at.
pub(crate) fn visit_horizontal_warichu(
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    laid_out: &LayoutParagraph,
    x: f32,
    y: f32,
    mut visit: impl FnMut(&mut LayoutParagraph, (f32, f32)),
) {
    let ranges = &plan.offsets.ranges;
    for (index, piece, rect) in horizontal_warichu_placeholders(paragraph, &plan.offsets, laid_out)
    {
        let (Some(span), Some(range)) = (paragraph.children().get(index), ranges.get(index)) else {
            continue;
        };
        let Some(mut style) = horizontal_span_style(laid_out, range) else {
            continue;
        };
        style.set_font_size(span.font_size * WARICHU_FONT_SCALE);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(span.letter_spacing * WARICHU_FONT_SCALE);
        let transformed = span.apply_text_transform();
        let text = match range.pieces.get(piece) {
            Some(piece) => piece_text(&transformed, piece),
            None => transformed,
        };
        let (first, second) = warichu_text_lines(&text);
        let mut first_para = mini_paragraph(first, &style, get_font_collection());
        let mut second_para = mini_paragraph(second, &style, get_font_collection());
        let half_height = rect.height() / 2.0;
        visit(&mut first_para, (x + rect.left(), y + rect.top()));
        visit(
            &mut second_para,
            (x + rect.left(), y + rect.top() + half_height),
        );
    }
}
