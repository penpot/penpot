use crate::render::options::RenderOptions;
use crate::shapes::{vertical_align_offset, Shape, TextContent, Type};
use crate::state::{TextEditorState, TextSelection};
use crate::view::Viewbox;
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
use skia_safe::{BlendMode, Canvas, Color, Paint, Point, Rect};

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
                // Empty paragraph - use default height
                (0.0, 0.0, 1.0, laid_out_para.height())
            } else if char_pos == 0 {
                let rects = laid_out_para.get_rects_for_range(
                    0..para.char_offset_to_utf16(para.next_grapheme_offset(0)),
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
                let last_start =
                    para.char_offset_to_utf16(para.prev_grapheme_offset(para_char_count));
                let rects = laid_out_para.get_rects_for_range(
                    last_start..para.char_offset_to_utf16(para_char_count),
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                );
                if !rects.is_empty() {
                    let r = &rects[0].rect;
                    (r.right(), r.top(), r.width(), r.height())
                } else if let Some(line) = laid_out_para.get_line_metrics().last() {
                    (
                        line.left as f32 + line.width as f32,
                        0.0,
                        1.0,
                        laid_out_para.height(),
                    )
                } else {
                    (0.0, 0.0, 1.0, laid_out_para.height())
                }
            } else {
                let utf16_pos = para.char_offset_to_utf16(char_pos);
                let rects = laid_out_para.get_rects_for_range(
                    utf16_pos..para.char_offset_to_utf16(para.next_grapheme_offset(char_pos)),
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
    range_rects(
        (start.paragraph, start.offset),
        (end.paragraph, end.offset),
        text_content,
        shape,
    )
}

/// Top-left corner of the first line box of the text between `start` and `end`
/// (paragraph, char offset), shape-local; `None` when the range covers no text.
pub(crate) fn range_origin(
    start: (usize, usize),
    end: (usize, usize),
    text_content: &TextContent,
    shape: &Shape,
) -> Option<Point> {
    range_rects(start, end, text_content, shape)
        .first()
        .map(|rect| Point::new(rect.left(), rect.top()))
}

/// Line boxes, shape-local, of the text between `start` and `end` (paragraph,
/// char offset).
fn range_rects(
    start: (usize, usize),
    end: (usize, usize),
    text_content: &TextContent,
    shape: &Shape,
) -> Vec<Rect> {
    let mut rects = Vec::new();
    let (start_paragraph, start_offset) = start;
    let (end_paragraph, end_offset) = end;

    let paragraphs = text_content.paragraphs();
    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();

    let mut y_offset = paragraphs_vertical_offset(shape, &layout_paragraphs);

    for (para_idx, laid_out_para) in layout_paragraphs.iter().enumerate() {
        let para_height = laid_out_para.height();

        // Check if this paragraph is in selection range
        if para_idx < start_paragraph || para_idx > end_paragraph {
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

        let range_start = if para_idx == start_paragraph {
            start_offset
        } else {
            0
        };

        let range_end = if para_idx == end_paragraph {
            end_offset
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::globals::TestRenderResourcesGuard;
    use crate::render::RenderResources;
    use crate::shapes::{
        FontFamily, FontStyle, GrowType, Paragraph, TextAlign, TextDirection, TextSpan,
    };
    use crate::uuid::Uuid;

    /// A laid-out fixed text, `width` wide, with a single span of `text`.
    fn text_shape(text: &str, width: f32) -> Shape {
        let span = TextSpan::new(
            text.to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            14.0,
            1.2,
            0.0,
            None,
            None,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            vec![],
        );
        let mut content =
            TextContent::new(Rect::from_xywh(0.0, 0.0, width, 100.0), GrowType::Fixed);
        content.add_paragraph(Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![span],
        ));

        let mut shape = Shape::new(Uuid::nil());
        shape.set_shape_type(Type::Text(content));
        shape.set_selrect(0.0, 0.0, width, 100.0);
        shape
    }

    fn origin_of(shape: &Shape, start: usize, end: usize) -> Option<Point> {
        let Type::Text(content) = &shape.shape_type else {
            unreachable!()
        };
        range_origin((0, start), (0, end), content, shape)
    }

    #[test]
    fn range_origin_is_where_the_text_starts() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = text_shape("hello world", 500.0);

        let hello = origin_of(&shape, 0, 5).expect("hello has text");
        let world = origin_of(&shape, 6, 11).expect("world has text");

        assert!(hello.x.abs() < 0.01, "{hello:?}");
        assert!((0.0..1.0).contains(&hello.y), "{hello:?}");
        assert!(world.x > hello.x, "{world:?}");
        assert!((world.y - hello.y).abs() < 0.01, "{world:?}");
    }

    #[test]
    fn range_origin_of_wrapped_text_is_on_its_first_line() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        // Too narrow for both words on one line.
        let shape = text_shape("hello world", 45.0);

        let world = origin_of(&shape, 6, 11).expect("world has text");

        assert!(world.x.abs() < 0.01, "{world:?}");
        assert!(world.y > 10.0, "{world:?}");
    }

    #[test]
    fn range_origin_is_none_for_an_empty_range() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = text_shape("hello", 500.0);

        assert!(origin_of(&shape, 2, 2).is_none());
    }
}
