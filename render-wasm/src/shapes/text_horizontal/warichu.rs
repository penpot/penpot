use super::*;

pub(super) const HORIZONTAL_WARICHU_BUILDER_LEN: usize = 3;
pub(super) const HORIZONTAL_WARICHU_STYLE_ANCHOR: char = '\u{00A0}';
pub(super) const HORIZONTAL_WARICHU_BREAK_ANCHOR: char = '\u{200B}';

/// Placeholder rect of each horizontal warichu span, keyed by span index.
/// Tabs in normal spans are placeholders too, so they are skipped.
pub(crate) fn horizontal_warichu_placeholders(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
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
    laid_out: &skia::textlayout::Paragraph,
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

pub(crate) fn horizontal_warichu_hit_test(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    point: Point,
) -> Option<usize> {
    for (span, source_start, _, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        if !rect.contains(&point) {
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
        let within = (fraction * line_len as f32).round() as usize;
        return Some(source_start + line_start + within.min(line_len));
    }
    None
}

pub(crate) fn horizontal_warichu_caret_rect(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
    source_offset: usize,
) -> Option<skia::Rect> {
    for (span, source_start, source_end, rect) in horizontal_warichu_boxes(paragraph, laid_out) {
        if source_offset < source_start || source_offset > source_end {
            continue;
        }
        let split = warichu_split_chars(&span.apply_text_transform());
        let local = source_offset - source_start;
        let total = source_end - source_start;
        let (line_start, line_len, top) = if local >= split {
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

pub(crate) fn horizontal_warichu_range_rects(
    paragraph: &Paragraph,
    laid_out: &skia::textlayout::Paragraph,
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
    laid_out: &skia::textlayout::Paragraph,
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
