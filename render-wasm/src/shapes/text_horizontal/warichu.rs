use super::*;
use crate::shapes::text_paragraph::LayoutParagraph;

pub(super) const HORIZONTAL_WARICHU_BUILDER_LEN: usize = 3;
pub(super) const HORIZONTAL_WARICHU_STYLE_ANCHOR: char = '\u{00A0}';
pub(super) const HORIZONTAL_WARICHU_BREAK_ANCHOR: char = '\u{200B}';

/// Placeholder rect of each horizontal warichu span, keyed by span index.
/// Tabs in normal spans are placeholders too, so they are skipped.
pub(crate) fn horizontal_warichu_placeholders(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
) -> Vec<(usize, skia::Rect)> {
    let mut placeholders = laid_out.get_rects_for_placeholders().into_iter();
    let mut result = Vec::new();
    for (index, span) in paragraph.children().iter().enumerate() {
        if span.is_warichu() {
            if let Some(textbox) = placeholders.next() {
                result.push((index, textbox.rect));
            }
        } else {
            for _ in span.text.matches('\t') {
                placeholders.next();
            }
        }
    }
    result
}

fn horizontal_warichu_boxes<'a>(
    paragraph: &'a Paragraph,
    laid_out: &LayoutParagraph,
) -> Vec<(&'a TextSpan, usize, usize, skia::Rect)> {
    let mut source_start = 0usize;
    let mut placeholders = horizontal_warichu_placeholders(paragraph, laid_out)
        .into_iter()
        .peekable();
    let mut boxes = Vec::new();
    for (index, span) in paragraph.children().iter().enumerate() {
        let length = span.text.chars().count();
        if let Some((_, rect)) = placeholders.next_if(|(span_index, _)| *span_index == index) {
            boxes.push((span, source_start, source_start + length, rect));
        }
        source_start += length;
    }
    boxes
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
/// second. The note takes the points over its width across its whole line:
/// above the note counts as the first sub-line, below it as the second.
pub(crate) fn horizontal_warichu_hit_test(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
    point: Point,
) -> Option<(usize, bool)> {
    for (span, source_start, _, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        let (band_top, band_bottom) = line_band(laid_out, &rect);
        if point.x < rect.left()
            || point.x >= rect.right()
            || point.y < band_top
            || point.y >= band_bottom
        {
            continue;
        }
        let split = warichu_split_chars(&span.apply_text_transform());
        let total = span.text.chars().count();
        let second = point.y >= rect.top() + rect.height() / 2.0;
        let (line_start, line_len) = if second {
            (split, total - split)
        } else {
            (0, split)
        };
        let fraction = ((point.x - rect.left()) / rect.width().max(0.01)).clamp(0.0, 1.0);
        let within = ((fraction * line_len as f32).round() as usize).min(line_len);
        let upstream = !second && within == line_len && line_len < total;
        return Some((source_start + line_start + within, upstream));
    }
    None
}

/// Caret rect at `source_offset` inside a warichu note, or None outside
/// every note. The offset where the sub-lines meet is drawn at the end of the
/// first sub-line when `upstream`, else at the start of the second.
pub(crate) fn horizontal_warichu_caret_rect(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
    source_offset: usize,
    upstream: bool,
) -> Option<skia::Rect> {
    for (span, source_start, source_end, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        if source_offset < source_start || source_offset > source_end {
            continue;
        }
        let split = warichu_split_chars(&span.apply_text_transform());
        let local = source_offset - source_start;
        let total = source_end - source_start;
        let second = local > split || (local == split && !upstream);
        let (line_start, line_len, top) = if second {
            (split, total - split, rect.top() + rect.height() / 2.0)
        } else {
            (0, split, rect.top())
        };
        let within = local.saturating_sub(line_start).min(line_len);
        let char_width = rect.width() / line_len.max(1) as f32;
        return Some(skia::Rect::from_xywh(
            rect.left() + within as f32 * char_width,
            top,
            char_width,
            rect.height() / 2.0,
        ));
    }
    None
}

/// Caret moved to the other sub-line of the warichu note holding
/// `source_offset`, keeping `x`: down from the first sub-line, up from the
/// second. None when the caret is outside every note or the move leaves it.
pub(crate) fn horizontal_warichu_step(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
    source_offset: usize,
    upstream: bool,
    x: f32,
    down: bool,
) -> Option<(usize, bool)> {
    let caret = horizontal_warichu_caret_rect(paragraph, laid_out, source_offset, upstream)?;
    let (_, _, _, rect) = horizontal_warichu_boxes(paragraph, laid_out)
        .into_iter()
        .find(|(_, start, end, _)| (*start..=*end).contains(&source_offset))?;
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
    horizontal_warichu_hit_test(paragraph, laid_out, Point::new(x, y))
}

pub(crate) fn horizontal_warichu_range_rects(
    paragraph: &Paragraph,
    laid_out: &LayoutParagraph,
    source_start: usize,
    source_end: usize,
) -> Vec<skia::Rect> {
    let mut rects = Vec::new();
    for (span, span_start, span_end, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        let selected_start = source_start.max(span_start);
        let selected_end = source_end.min(span_end);
        if selected_start >= selected_end {
            continue;
        }
        let split = warichu_split_chars(&span.apply_text_transform());
        for (line_start, line_end, top) in [
            (span_start, span_start + split, rect.top()),
            (
                span_start + split,
                span_end,
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
    let ranges = &plan.offsets.ranges;
    let placeholders = horizontal_warichu_placeholders(paragraph, laid_out);
    let warichu_ranges: Vec<_> = ranges.iter().filter(|range| range.warichu).collect();
    if placeholders.len() != warichu_ranges.len() {
        return;
    }

    for (range, (_, rect)) in warichu_ranges.into_iter().zip(placeholders) {
        let Some(span) = paragraph.children().get(range.span) else {
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
        let (first, second) = warichu_text_lines(&transformed);
        let first_para = mini_paragraph(first, &style, get_font_collection());
        let second_para = mini_paragraph(second, &style, get_font_collection());
        let half_height = rect.height() / 2.0;
        first_para.paint(canvas, (x + rect.left(), y + rect.top()));
        second_para.paint(canvas, (x + rect.left(), y + rect.top() + half_height));
    }
}
