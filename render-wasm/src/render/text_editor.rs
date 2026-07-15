use crate::render::options::RenderOptions;
use crate::shapes::text_vertical;
use crate::shapes::{Shape, TextContent, Type, VerticalAlign};
use crate::state::{TextEditorState, TextSelection};
use crate::view::Viewbox;
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
use skia_safe::{BlendMode, Canvas, Color, Paint, Rect};

pub fn render_overlay(
    canvas: &Canvas,
    viewbox: &Viewbox,
    options: &RenderOptions,
    editor_state: &TextEditorState,
    shape: &Shape,
) {
    if !editor_state.has_focus {
        return;
    }

    let Type::Text(text_content) = &shape.shape_type else {
        return;
    };

    canvas.save();
    let zoom = viewbox.zoom * options.dpr;
    canvas.scale((zoom, zoom));
    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    if editor_state.selection.is_selection() {
        render_selection(canvas, editor_state, text_content, shape);
    }

    if editor_state.cursor_visible {
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

    // In vertical writing the caret is a thin horizontal bar across the
    // column; in horizontal writing a thin vertical bar.
    let thin = editor_state.theme.cursor_width / zoom * dpr;
    let mut cursor_rect = Rect::new_empty();
    if text_content.is_vertical() {
        cursor_rect.set_xywh(
            rect.x(),
            rect.y(),
            rect.width(),
            if editor_state.is_overtype_mode && rect.height() > 0.0 {
                rect.height()
            } else {
                thin
            },
        );
    } else {
        cursor_rect.set_xywh(
            rect.x(),
            rect.y(),
            if editor_state.is_overtype_mode {
                rect.width()
            } else {
                thin
            },
            rect.height(),
        );
    }

    let mut paint = Paint::default();
    paint.set_anti_alias(false);
    if editor_state.is_overtype_mode {
        paint.set_blend_mode(BlendMode::Exclusion);
        paint.set_color(Color::WHITE);
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

fn vertical_layout_for_shape(
    text_content: &TextContent,
    shape: &Shape,
) -> (text_vertical::VerticalLayout, f32) {
    let selrect = shape.selrect();
    let max_height = text_vertical::wrap_height(text_content, selrect.height());
    let layout = text_vertical::layout_from_content(text_content, max_height);
    let origin_x =
        text_vertical::block_axis_offset(selrect.width(), layout.width, shape.vertical_align());
    (layout, origin_x)
}

fn calculate_vertical_cursor_rect(
    text_content: &TextContent,
    shape: &Shape,
    paragraph: usize,
    offset: usize,
) -> Option<Rect> {
    let (layout, origin_x) = vertical_layout_for_shape(text_content, shape);
    let rect = text_vertical::caret_rect(&layout, paragraph, offset)?;
    Some(Rect::from_xywh(
        origin_x + rect.x(),
        rect.y(),
        rect.width(),
        rect.height(),
    ))
}

fn calculate_vertical_selection_rects(
    selection: &TextSelection,
    text_content: &TextContent,
    shape: &Shape,
) -> Vec<Rect> {
    let start = selection.start();
    let end = selection.end();
    let paragraphs = text_content.paragraphs();
    let (layout, origin_x) = vertical_layout_for_shape(text_content, shape);
    let mut rects = Vec::new();

    for (para_idx, paragraph) in paragraphs
        .iter()
        .enumerate()
        .take(end.paragraph + 1)
        .skip(start.paragraph)
    {
        let para_char_count: usize = paragraph
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
        for rect in text_vertical::range_rects(&layout, para_idx, range_start, range_end) {
            rects.push(Rect::from_xywh(
                origin_x + rect.x(),
                rect.y(),
                rect.width(),
                rect.height(),
            ));
        }
    }
    rects
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

    if text_content.is_vertical() {
        return calculate_vertical_cursor_rect(
            text_content,
            shape,
            cursor.paragraph,
            cursor.offset,
        );
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
            if let Some(rect) =
                crate::shapes::horizontal_warichu_caret_rect(para, laid_out_para, char_pos)
            {
                return Some(Rect::from_xywh(
                    rect.x(),
                    y_offset + rect.y(),
                    rect.width(),
                    rect.height(),
                ));
            }
            let para_char_count: usize = para
                .children()
                .iter()
                .map(|span| span.text.chars().count())
                .sum();

            // Cursor offsets live in original text space; the laid-out
            // paragraph indexes the kinsoku-shifted builder text.
            let (cursor_x, cursor_y, cursor_width, cursor_height) = if para_char_count == 0 {
                // Empty paragraph - use default height
                (0.0, 0.0, 1.0, laid_out_para.height())
            } else if char_pos == 0 {
                let rects = laid_out_para.get_rects_for_range(
                    0..1,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (r.left(), r.top(), r.width(), r.height())
                } else {
                    (0.0, 0.0, 1.0, laid_out_para.height())
                }
            } else if char_pos >= para_char_count {
                let last_pos = crate::shapes::horizontal_source_to_builder(
                    para,
                    para_char_count.saturating_sub(1),
                );
                let rects = laid_out_para.get_rects_for_range(
                    last_pos..last_pos + 1,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (r.right(), r.top(), r.width(), r.height())
                } else {
                    (
                        laid_out_para.longest_line(),
                        0.0,
                        1.0,
                        laid_out_para.height(),
                    )
                }
            } else {
                let shifted_pos = crate::shapes::horizontal_source_to_builder(para, char_pos);
                let rects = laid_out_para.get_rects_for_range(
                    shifted_pos..shifted_pos + 1,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (r.left(), r.top(), r.width(), r.height())
                } else {
                    // Fallback: use glyph position
                    let pos = laid_out_para.get_glyph_position_at_coordinate((0.0, 0.0));
                    (pos.position as f32, 0.0, 1.0, laid_out_para.height())
                }
            };

            return Some(Rect::from_xywh(
                cursor_x,
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
    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs();

    if text_content.is_vertical() {
        return calculate_vertical_selection_rects(selection, text_content, shape);
    }

    let mut rects = Vec::new();

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
            // Selection offsets live in original text space; the
            // laid-out paragraph indexes the kinsoku-shifted text.
            let warichu_rects = crate::shapes::horizontal_warichu_range_rects(
                para,
                laid_out_para,
                range_start,
                range_end,
            );
            for r in warichu_rects {
                rects.push(Rect::from_xywh(
                    r.left(),
                    y_offset + r.top(),
                    r.width(),
                    r.height(),
                ));
            }
            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            for builder_range in
                crate::shapes::horizontal_normal_selection_ranges(para, range_start, range_end)
            {
                for text_box in laid_out_para.get_rects_for_range(
                    builder_range,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                ) {
                    let r = text_box.rect;
                    rects.push(Rect::from_xywh(
                        r.left(),
                        y_offset + r.top(),
                        r.width(),
                        r.height(),
                    ));
                }
            }
        }

        y_offset += para_height;
    }

    rects
}
