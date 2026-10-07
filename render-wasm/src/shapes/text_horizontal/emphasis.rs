use super::*;

#[derive(Debug, Clone)]
pub(crate) struct HorizontalEmphasisPlacement {
    pub(crate) span: usize,
    /// UTF-16 range of the base character in the span's transformed text.
    pub(crate) range: std::ops::Range<usize>,
    pub(crate) mark: char,
    /// Base character rect in the laid-out paragraph.
    pub(crate) rect: skia::Rect,
}

/// Place one emphasis mark above each eligible character, from the laid-out
/// rect of each character so marks follow SkParagraph's wrapping and bidi.
pub(crate) fn horizontal_emphasis_placements(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &skia::textlayout::Paragraph,
) -> Vec<HorizontalEmphasisPlacement> {
    let offset_map = &offsets.offset_map;
    let mut placements = Vec::new();

    for range in offsets.ranges.iter().filter(|range| !range.warichu) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        let Some(mark) = span.text_emphasis.mark_char() else {
            continue;
        };
        let transformed = span.apply_text_transform();
        let mut local_utf16 = 0usize;
        for character in transformed.chars() {
            let next_utf16 = local_utf16 + character.len_utf16();
            if emphasis_char_allowed(character) {
                let shifted_start = offset_map.to_shifted(range.source_start + local_utf16);
                let shifted_end = offset_map.to_shifted(range.source_start + next_utf16);
                let builder_start =
                    range.builder_start + shifted_start.saturating_sub(range.shifted_start);
                let builder_end =
                    range.builder_start + shifted_end.saturating_sub(range.shifted_start);
                let scalar_rect = laid_out
                    .get_rects_for_range(
                        builder_start..builder_end,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    )
                    .into_iter()
                    .map(|textbox| textbox.rect)
                    .reduce(|mut rect, next| {
                        rect.join(next);
                        rect
                    });
                if let Some(rect) = scalar_rect {
                    placements.push(HorizontalEmphasisPlacement {
                        span: range.span,
                        range: local_utf16..next_utf16,
                        mark,
                        rect,
                    });
                }
            }
            local_utf16 = next_utf16;
        }
    }
    placements
}

/// Em box of a horizontal emphasis mark in the laid-out paragraph: centred
/// over its base character, outside any stacked ruby layer.
pub(crate) fn horizontal_emphasis_mark_box(
    span: &TextSpan,
    placement: &HorizontalEmphasisPlacement,
) -> skia::Rect {
    let size = span.font_size * EMPHASIS_FONT_SCALE;
    let bottom = horizontal_annotation_over_top(placement.rect, span.font_size)
        - span.emphasis_ruby_offset();
    skia::Rect::from_xywh(
        placement.rect.center_x() - size / 2.0,
        bottom - size,
        size,
        size,
    )
}

/// Paint horizontal emphasis marks (圏点 / bouten) above their base glyphs.
/// Draw-only: the base paragraph keeps its own metrics.
pub(crate) fn paint_horizontal_emphasis(
    canvas: &skia::Canvas,
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    laid_out: &skia::textlayout::Paragraph,
    x: f32,
    y: f32,
) {
    let offsets = &plan.offsets;
    let placements = horizontal_emphasis_placements(paragraph, offsets, laid_out);
    for range in offsets.ranges.iter().filter(|range| !range.warichu) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        let Some(mark) = span.text_emphasis.mark_char() else {
            continue;
        };
        let Some(mut style) = horizontal_span_style(laid_out, range) else {
            continue;
        };
        style.set_font_size(span.font_size * EMPHASIS_FONT_SCALE);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(0.0);
        let mark_paragraph = mini_paragraph(&mark.to_string(), &style, get_font_collection());
        let mark_ink = mark_paragraph
            .get_rects_for_range(
                0..mark.len_utf16(),
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            )
            .into_iter()
            .map(|textbox| textbox.rect)
            .reduce(|mut rect, next| {
                rect.join(next);
                rect
            });
        let mark_width = mark_ink
            .as_ref()
            .map(|rect| rect.width())
            .unwrap_or_else(|| mark_paragraph.longest_line());
        let mark_ink_bottom = mark_ink
            .as_ref()
            .map(|rect| rect.bottom())
            .unwrap_or_else(|| mark_paragraph.height());
        for placement in placements
            .iter()
            .filter(|placement| placement.span == range.span && placement.mark == mark)
        {
            let mark_x = x + placement.rect.center_x() - mark_width / 2.0;
            let mark_y = y + horizontal_annotation_over_top(placement.rect, span.font_size)
                - mark_ink_bottom
                - span.emphasis_ruby_offset();
            mark_paragraph.paint(canvas, (mark_x, mark_y));
        }
    }
}
