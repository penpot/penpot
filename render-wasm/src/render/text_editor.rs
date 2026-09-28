use crate::render::options::RenderOptions;
use crate::shapes::{vertical_align_offset, Shape, TextContent, Type};
use crate::state::{TextEditorState, TextSelection};
use crate::view::Viewbox;
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle, TextBox, TextDirection};
use skia_safe::{BlendMode, Canvas, Color, Paint, Rect};

/// Caret x where a character typed *before* this glyph would land.
fn leading_edge(text_box: &TextBox) -> f32 {
    match text_box.direct {
        TextDirection::RTL => text_box.rect.right(),
        _ => text_box.rect.left(),
    }
}

/// Caret x where a character typed *after* this glyph would land.
fn trailing_edge(text_box: &TextBox) -> f32 {
    match text_box.direct {
        TextDirection::RTL => text_box.rect.left(),
        _ => text_box.rect.right(),
    }
}

pub fn render_overlay(
    canvas: &Canvas,
    viewbox: &Viewbox,
    options: &RenderOptions,
    editor_state: &TextEditorState,
    shape: &Shape,
) {
    let has_selection = editor_state.selection.is_selection();

    if !editor_state.has_focus && !has_selection {
        return;
    }

    let Type::Text(text_content) = &shape.shape_type else {
        return;
    };

    canvas.save();
    let zoom = viewbox.zoom * options.dpr;
    canvas.scale((zoom, zoom));
    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    if has_selection {
        // With an active selection there is no blinking caret (the caret is one
        // end of the selection); drawing it would make it toggle on top of the
        // highlight while the selection is held.
        render_selection(canvas, editor_state, text_content, shape);
    } else if editor_state.has_focus && editor_state.cursor_visible {
        render_cursor(canvas, zoom, options.dpr, editor_state, text_content, shape);
    }

    canvas.restore();
}

fn render_cursor(
    canvas: &Canvas,
    zoom: f32,
    dpr: f32,
    editor_state: &TextEditorState,
    text_content: &TextContent,
    shape: &Shape,
) {
    let Some(rect) = calculate_cursor_rect(editor_state, text_content, shape) else {
        return;
    };

    let mut cursor_rect = Rect::new_empty();
    cursor_rect.set_xywh(
        rect.x(),
        rect.y(),
        if editor_state.is_overtype_mode {
            rect.width()
        } else {
            editor_state.theme.cursor_width / zoom * dpr
        },
        rect.height(),
    );

    let mut paint = Paint::default();
    paint.set_anti_alias(false);
    if editor_state.is_overtype_mode {
        paint.set_blend_mode(BlendMode::Exclusion);
        paint.set_color(Color::WHITE);
    } else if editor_state.theme.cursor_invert {
        // Default (no solid fill to match): a white caret with a Difference
        // blend renders the inverted color of whatever is behind it.
        paint.set_blend_mode(BlendMode::Difference);
        paint.set_color(editor_state.theme.cursor_color);
    } else {
        paint.set_blend_mode(BlendMode::SrcOver);
        paint.set_color(editor_state.theme.cursor_color);
    }

    let shape_matrix = shape.get_matrix();
    canvas.save();
    canvas.concat(&shape_matrix);
    canvas.draw_rect(cursor_rect, &paint);
    canvas.restore();
}

fn render_selection(
    canvas: &Canvas,
    editor_state: &TextEditorState,
    text_content: &TextContent,
    shape: &Shape,
) {
    let selection = &editor_state.selection;
    let rects = calculate_selection_rects(selection, text_content, shape);

    if rects.is_empty() {
        return;
    }

    let mut paint = Paint::default();
    paint.set_blend_mode(BlendMode::default());
    paint.set_color(editor_state.theme.selection_color);
    paint.set_anti_alias(true);

    let shape_matrix = shape.get_matrix();
    canvas.save();
    canvas.concat(&shape_matrix);
    for rect in rects {
        canvas.draw_rect(rect, &paint);
    }
    canvas.restore();
}

/// Caret and selection rects are drawn through `shape.get_matrix()`, which
/// translates by the *selrect's* `left_top()`; this shifts them onto the text.
fn paragraphs_horizontal_offset(shape: &Shape, text_content: &TextContent) -> f32 {
    let selrect = shape.selrect();
    let width = text_content.get_width(selrect.width());
    text_content.layout_origin_x(&selrect, width) - selrect.x()
}

fn paragraphs_vertical_offset(
    shape: &Shape,
    layout_paragraphs: &[&skia_safe::textlayout::Paragraph],
) -> f32 {
    let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
    vertical_align_offset(
        shape.selrect().height(),
        total_height,
        shape.vertical_align(),
    )
}

fn calculate_cursor_rect(
    editor_state: &TextEditorState,
    text_content: &TextContent,
    shape: &Shape,
) -> Option<Rect> {
    let cursor = editor_state.selection.focus;
    let paragraphs = text_content.paragraphs();
    if cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();

    if cursor.paragraph >= layout_paragraphs.len() {
        return None;
    }

    let x_offset = paragraphs_horizontal_offset(shape, text_content);
    let mut y_offset = paragraphs_vertical_offset(shape, &layout_paragraphs);
    for (idx, laid_out_para) in layout_paragraphs.iter().enumerate() {
        if idx == cursor.paragraph {
            let char_pos = cursor.offset;
            // For cursor, we get a zero-width range at the position
            // We need to handle edge cases:
            // - At start of paragraph: use position 0
            // - At end of paragraph: use last position
            let para = &paragraphs[cursor.paragraph];
            let para_char_count: usize = para
                .children()
                .iter()
                .map(|span| span.text.chars().count())
                .sum();

            // Skia ranges are UTF-16 code units, not characters.
            let (cursor_x, cursor_y, cursor_width, cursor_height) = if para_char_count == 0 {
                // No glyph to anchor to: sit where the first character will appear.
                let empty_x = if para.text_direction() == TextDirection::RTL {
                    laid_out_para.max_width()
                } else {
                    0.0
                };
                (empty_x, 0.0, 1.0, laid_out_para.height())
            } else if char_pos == 0 {
                let rects = laid_out_para.get_rects_for_range(
                    0..para.char_utf16_len_at(0),
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (leading_edge(&rects[0]), r.top(), r.width(), r.height())
                } else {
                    (0.0, 0.0, 1.0, laid_out_para.height())
                }
            } else if char_pos >= para_char_count {
                let last_char = para_char_count.saturating_sub(1);
                let last_start = para.char_offset_to_utf16(last_char);
                let rects = laid_out_para.get_rects_for_range(
                    last_start..last_start + para.char_utf16_len_at(last_char),
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (trailing_edge(&rects[0]), r.top(), r.width(), r.height())
                } else if let Some(line) = laid_out_para.get_line_metrics().last() {
                    // No glyph box to measure: use the end of the line.
                    let line_end = if para.text_direction() == TextDirection::RTL {
                        line.left as f32
                    } else {
                        line.left as f32 + line.width as f32
                    };
                    (line_end, 0.0, 1.0, laid_out_para.height())
                } else {
                    (0.0, 0.0, 1.0, laid_out_para.height())
                }
            } else {
                let utf16_pos = para.char_offset_to_utf16(char_pos);
                let rects = laid_out_para.get_rects_for_range(
                    utf16_pos..utf16_pos + para.char_utf16_len_at(char_pos),
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (leading_edge(&rects[0]), r.top(), r.width(), r.height())
                } else {
                    // Fallback: use glyph position
                    let pos = laid_out_para.get_glyph_position_at_coordinate((0.0, 0.0));
                    (pos.position as f32, 0.0, 1.0, laid_out_para.height())
                }
            };

            return Some(Rect::from_xywh(
                x_offset + cursor_x,
                y_offset + cursor_y,
                cursor_width, // cursor_width
                cursor_height,
            ));
        }
        y_offset += laid_out_para.height();
    }

    None
}

fn calculate_selection_rects(
    selection: &TextSelection,
    text_content: &TextContent,
    shape: &Shape,
) -> Vec<Rect> {
    let mut rects = Vec::new();

    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs();
    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();

    let x_offset = paragraphs_horizontal_offset(shape, text_content);
    let mut y_offset = paragraphs_vertical_offset(shape, &layout_paragraphs);

    for (para_idx, laid_out_para) in layout_paragraphs.iter().enumerate() {
        let para_height = laid_out_para.height();

        // Check if this paragraph is in selection range
        if para_idx < start.paragraph || para_idx > end.paragraph {
            y_offset += para_height;
            continue;
        }

        // Calculate character range for this paragraph
        let para = &paragraphs[para_idx];
        let para_char_count: usize = para
            .children()
            .iter()
            .map(|span| span.text.chars().count())
            .sum();

        let range_start = if para_idx == start.paragraph {
            start.offset
        } else {
            0
        };

        let range_end = if para_idx == end.paragraph {
            end.offset
        } else {
            para_char_count
        };

        if range_start < range_end {
            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            let text_boxes = laid_out_para.get_rects_for_range(
                para.char_offset_to_utf16(range_start)..para.char_offset_to_utf16(range_end),
                RectHeightStyle::Max,
                RectWidthStyle::Tight,
            );

            for text_box in text_boxes {
                let r = text_box.rect;
                rects.push(Rect::from_xywh(
                    x_offset + r.left(),
                    y_offset + r.top(),
                    r.width(),
                    r.height(),
                ));
            }
        }

        y_offset += para_height;
    }

    rects
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::{FontFamily, FontStyle, GrowType, Paragraph, TextAlign, TextSpan};
    use crate::uuid::Uuid;

    fn rtl_span() -> TextSpan {
        TextSpan::new(
            "نام".to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            14.0,
            1.2,
            0.0,
            None,
            None,
            TextDirection::RTL,
            400,
            Uuid::nil(),
            vec![],
        )
    }

    /// Auto-width rtl content measured wider than its stale selrect: mid-edit,
    /// where the overlay origin and the selrect diverge.
    fn grown_rtl_shape(measured_width: f32) -> Shape {
        let selrect = Rect::from_xywh(100.0, 50.0, 60.0, 20.0);
        let mut content = TextContent::new(selrect, GrowType::AutoWidth);
        content.add_paragraph(Paragraph::new(
            TextAlign::Right,
            TextDirection::RTL,
            None,
            None,
            1.2,
            0.0,
            vec![rtl_span()],
        ));
        content.size.width = measured_width;
        content.size.height = 20.0;

        let mut shape = Shape::new(Uuid::nil());
        shape.set_selrect(selrect.left, selrect.top, selrect.right, selrect.bottom);
        shape.set_shape_type(Type::Text(content));
        shape
    }

    fn text_content_of(shape: &Shape) -> &TextContent {
        match &shape.shape_type {
            Type::Text(content) => content,
            _ => unreachable!(),
        }
    }

    #[test]
    fn horizontal_offset_shifts_the_overlay_onto_grown_rtl_text() {
        let shape = grown_rtl_shape(120.0);
        // right edge 160 - measured 120 = 40, i.e. 60 left of selrect.x
        assert_eq!(
            paragraphs_horizontal_offset(&shape, text_content_of(&shape)),
            -60.0
        );
    }

    #[test]
    fn horizontal_offset_is_zero_once_the_selrect_is_committed() {
        let shape = grown_rtl_shape(60.0);
        assert_eq!(
            paragraphs_horizontal_offset(&shape, text_content_of(&shape)),
            0.0
        );
    }

    #[test]
    fn leading_and_trailing_edges_follow_the_run_direction() {
        let rect = Rect::from_ltrb(10.0, 0.0, 30.0, 12.0);
        let ltr = TextBox {
            rect,
            direct: TextDirection::LTR,
        };
        let rtl = TextBox {
            rect,
            direct: TextDirection::RTL,
        };
        assert_eq!(leading_edge(&ltr), 10.0);
        assert_eq!(trailing_edge(&ltr), 30.0);
        assert_eq!(leading_edge(&rtl), 30.0);
        assert_eq!(trailing_edge(&rtl), 10.0);
    }
}
