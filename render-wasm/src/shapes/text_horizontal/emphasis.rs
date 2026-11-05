use super::*;
use crate::shapes::text_paragraph::LayoutParagraph;

#[derive(Debug, Clone)]
pub(crate) struct HorizontalEmphasisPlacement {
    pub(crate) span: usize,
    /// UTF-16 range of the base character in the span's transformed text.
    pub(crate) range: std::ops::Range<usize>,
    pub(crate) mark: char,
    /// Base character rect in the laid-out paragraph.
    pub(crate) rect: skia::Rect,
    /// Top of the base character's em box (`horizontal_base_em_top`).
    pub(crate) em_top: f32,
}

/// Place one emphasis mark beside each eligible character, from the laid-out
/// rect of each character so marks follow SkParagraph's wrapping and bidi.
pub(crate) fn horizontal_emphasis_placements(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
) -> Vec<HorizontalEmphasisPlacement> {
    let offset_map = &offsets.offset_map;
    let lines = laid_out.get_line_metrics();
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
                        em_top: horizontal_base_em_top(
                            laid_out,
                            &lines,
                            builder_start,
                            span.font_size,
                            rect,
                        ),
                    });
                }
            }
            local_utf16 = next_utf16;
        }
    }
    placements
}

/// One emphasis mark shaped at its span's style. Paint, bounds and position
/// data all place this measurement, so they agree on where the mark is.
pub(crate) struct HorizontalEmphasisMark {
    paragraph: LayoutParagraph,
    /// Tight rect of the mark in `paragraph`.
    pub(crate) ink: skia::Rect,
}

impl HorizontalEmphasisMark {
    /// Shape `mark` at the style of `range` in the laid-out paragraph.
    pub(crate) fn measure(
        laid_out: &LayoutParagraph,
        range: &HorizontalSpanRange,
        span: &TextSpan,
        mark: char,
    ) -> Option<Self> {
        let mut style = horizontal_span_style(laid_out, range)?;
        style.set_font_size(span.font_size * EMPHASIS_FONT_SCALE);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(0.0);
        let paragraph = mini_paragraph(&mark.to_string(), &style, get_font_collection());
        let ink = paragraph
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
            })
            .unwrap_or_else(|| skia::Rect::from_wh(paragraph.longest_line(), paragraph.height()));
        Some(Self { paragraph, ink })
    }
}

/// Box of a horizontal emphasis mark whose ink is `ink`, in the laid-out
/// paragraph: centred beside its base character on the selected side,
/// outside stacked annotations. This is exactly where the mark paints.
pub(crate) fn horizontal_emphasis_mark_box(
    span: &TextSpan,
    placement: &HorizontalEmphasisPlacement,
    ink: &skia::Rect,
) -> skia::Rect {
    let offset = span.emphasis_outside_offset(false);
    let top = if span.emphasis_side(false) == AnnotationSide::Over {
        placement.em_top - offset - ink.height()
    } else {
        placement.rect.bottom() + offset
    };
    skia::Rect::from_xywh(
        placement.rect.center_x() - ink.width() / 2.0,
        top,
        ink.width(),
        ink.height(),
    )
}

/// Em box of a mark painted in `ink_box`, centred on it. Position data
/// carries it: SVG export draws the mark at a font size of its width.
pub(crate) fn horizontal_emphasis_mark_em_box(span: &TextSpan, ink_box: &skia::Rect) -> skia::Rect {
    let size = span.font_size * EMPHASIS_FONT_SCALE;
    skia::Rect::from_xywh(
        ink_box.center_x() - size / 2.0,
        ink_box.center_y() - size / 2.0,
        size,
        size,
    )
}

/// Call `f` with each placed mark, its span, its shaped mark and the box
/// it paints in, shaping one mark per span range.
fn for_each_horizontal_emphasis_mark(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
    mut f: impl FnMut(&TextSpan, HorizontalEmphasisPlacement, &HorizontalEmphasisMark, skia::Rect),
) {
    let mut placements = horizontal_emphasis_placements(paragraph, offsets, laid_out);
    for range in offsets.ranges.iter().filter(|range| !range.warichu) {
        let Some(span) = paragraph.children().get(range.span) else {
            continue;
        };
        let Some(mark) = span.text_emphasis.mark_char() else {
            continue;
        };
        let Some(shaped) = HorizontalEmphasisMark::measure(laid_out, range, span, mark) else {
            continue;
        };
        let (own, rest) = placements
            .into_iter()
            .partition(|placement| placement.span == range.span && placement.mark == mark);
        placements = rest;
        for placement in own {
            let ink_box = horizontal_emphasis_mark_box(span, &placement, &shaped.ink);
            f(span, placement, &shaped, ink_box);
        }
    }
}

/// Every horizontal emphasis mark with the box it paints in, in the
/// laid-out paragraph. Text bounds join these boxes.
pub(crate) fn horizontal_emphasis_mark_boxes(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
    laid_out: &LayoutParagraph,
) -> Vec<(HorizontalEmphasisPlacement, skia::Rect)> {
    let mut boxes = Vec::new();
    for_each_horizontal_emphasis_mark(paragraph, offsets, laid_out, |_, placement, _, ink_box| {
        boxes.push((placement, ink_box));
    });
    boxes
}

/// Paint horizontal emphasis marks on their configured side of the base.
/// Draw-only: the base paragraph keeps its own metrics.
pub(crate) fn paint_horizontal_emphasis(
    canvas: &skia::Canvas,
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    laid_out: &LayoutParagraph,
    x: f32,
    y: f32,
) {
    for_each_horizontal_emphasis_mark(
        paragraph,
        &plan.offsets,
        laid_out,
        |_, _, shaped, ink_box| {
            let origin = (
                x + ink_box.left - shaped.ink.left,
                y + ink_box.top - shaped.ink.top,
            );
            shaped.paragraph.paint(canvas, origin);
        },
    );
}
