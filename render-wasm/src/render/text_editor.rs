use crate::shapes::{Shape, TextContent, Type, VerticalAlign};
use crate::state::{TextEditorState, TextSelection};
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
use skia_safe::{BlendMode, Canvas, Matrix, Paint, Rect};

pub fn render_overlay(
    canvas: &Canvas,
    editor_state: &TextEditorState,
    shape: &Shape,
    transform: &Matrix,
) {
    if !editor_state.is_active {
        return;
    }

    let Type::Text(text_content) = &shape.shape_type else {
        return;
    };

    canvas.save();
    canvas.concat(transform);

    if editor_state.selection.is_selection() {
        render_selection(canvas, editor_state, text_content, shape);
    }

    if editor_state.cursor_visible {
        render_cursor(canvas, editor_state, text_content, shape);
    }

    canvas.restore();
}

fn render_cursor(
    canvas: &Canvas,
    editor_state: &TextEditorState,
    text_content: &TextContent,
    shape: &Shape,
) {
    let Some(rect) = calculate_cursor_rect(editor_state, text_content, shape) else {
        return;
    };

    let mut paint = Paint::default();
    paint.set_color(editor_state.theme.cursor_color);
    paint.set_anti_alias(true);

    let shape_matrix = shape.get_matrix();
    canvas.save();
    canvas.concat(&shape_matrix);
    canvas.draw_rect(rect, &paint);
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

fn vertical_align_offset(
    shape: &Shape,
    layout_paragraphs: &[&skia_safe::textlayout::Paragraph],
) -> f32 {
    let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
    match shape.vertical_align() {
        VerticalAlign::Center => (shape.selrect().height() - total_height) / 2.0,
        VerticalAlign::Bottom => shape.selrect().height() - total_height,
        _ => 0.0,
    }
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

    let mut y_offset = vertical_align_offset(shape, &layout_paragraphs);
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

            let layout_char_pos = para.logical_to_layout_offset(char_pos);
            let layout_para_count = para.logical_to_layout_offset(para_char_count);

            let (cursor_x, cursor_height) = if para_char_count == 0 {
                // Empty paragraph - use default height
                (0.0, laid_out_para.height())
            } else if char_pos == 0 {
                let rects = laid_out_para.get_rects_for_range(
                    0..1,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    (rects[0].rect.left(), rects[0].rect.height())
                } else {
                    (0.0, laid_out_para.height())
                }
            } else if char_pos >= para_char_count {
                let rects = laid_out_para.get_rects_for_range(
                    layout_para_count.saturating_sub(1)..layout_para_count,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    (rects[0].rect.right(), rects[0].rect.height())
                } else {
                    (laid_out_para.longest_line(), laid_out_para.height())
                }
            } else {
                let rects = laid_out_para.get_rects_for_range(
                    layout_char_pos..layout_char_pos + 1,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    (rects[0].rect.left(), rects[0].rect.height())
                } else {
                    // Fallback: use glyph position
                    let pos = laid_out_para.get_glyph_position_at_coordinate((0.0, 0.0));
                    (pos.position as f32, laid_out_para.height())
                }
            };

            return Some(Rect::from_xywh(
                cursor_x,
                y_offset,
                editor_state.theme.cursor_width,
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

    let mut y_offset = vertical_align_offset(shape, &layout_paragraphs);

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
            let layout_range_start = para.logical_to_layout_offset(range_start);
            let layout_range_end = para.logical_to_layout_offset(range_end);
            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            let text_boxes = laid_out_para.get_rects_for_range(
                layout_range_start..layout_range_end,
                RectHeightStyle::Max,
                RectWidthStyle::Tight,
            );

            for text_box in text_boxes {
                let r = text_box.rect;
                rects.push(Rect::from_xywh(
                    r.left(),
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
