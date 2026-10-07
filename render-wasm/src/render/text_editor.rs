use crate::render::options::RenderOptions;
use crate::shapes::text_vertical;
use crate::shapes::{vertical_align_offset, Shape, TextContent, Type};
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
    let Some(rect) = cursor_rect(editor_state, text_content, shape) else {
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

fn vertical_layout_for_shape(
    text_content: &TextContent,
    shape: &Shape,
) -> (std::rc::Rc<text_vertical::VerticalLayout>, f32) {
    let selrect = shape.selrect();
    let layout = text_content.vertical_layout(&selrect);
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

/// Caret rectangle relative to the shape's top-left corner.
pub(crate) fn cursor_rect(
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
    let laid_out_para = layout_paragraphs.get(cursor.paragraph)?;
    let y_offset = paragraphs_vertical_offset(shape, &layout_paragraphs)
        + layout_paragraphs[..cursor.paragraph]
            .iter()
            .map(|paragraph| paragraph.height())
            .sum::<f32>();
    let rect = horizontal_caret_rect(&paragraphs[cursor.paragraph], laid_out_para, cursor.offset);
    Some(rect.with_offset((0.0, y_offset)))
}

/// Caret rect at the source character offset `char_pos` of a laid-out
/// horizontal paragraph, in the paragraph's coordinates. It covers the
/// character at the offset (overtype carets use its width), or follows the
/// last character at the paragraph end.
fn horizontal_caret_rect(
    para: &crate::shapes::Paragraph,
    laid_out_para: &skia_safe::textlayout::Paragraph,
    char_pos: usize,
) -> Rect {
    if let Some(rect) = crate::shapes::horizontal_warichu_caret_rect(para, laid_out_para, char_pos)
    {
        return rect;
    }
    let para_char_count: usize = para
        .children()
        .iter()
        .map(|span| span.text.chars().count())
        .sum();
    let default_rect = || Rect::from_xywh(0.0, 0.0, 1.0, laid_out_para.height());
    if para_char_count == 0 {
        return default_rect();
    }

    // Cursor offsets count source characters; the laid-out paragraph indexes
    // the transformed, kinsoku-shifted builder text.
    let offsets = crate::shapes::HorizontalOffsets::new(para);
    let first_rect = |start: usize, end: usize| {
        laid_out_para
            .get_rects_for_range(start..end, RectHeightStyle::Max, RectWidthStyle::Tight)
            .first()
            .map(|textbox| textbox.rect)
    };
    if char_pos >= para_char_count {
        let last_start = offsets.source_to_builder(para.prev_grapheme_offset(para_char_count));
        let last_end = offsets.source_to_builder(para_char_count);
        return match first_rect(last_start, last_end) {
            Some(r) => Rect::from_xywh(r.right(), r.top(), r.width(), r.height()),
            None => match laid_out_para.get_line_metrics().last() {
                Some(line) => Rect::from_xywh(
                    line.left as f32 + line.width as f32,
                    0.0,
                    1.0,
                    laid_out_para.height(),
                ),
                None => default_rect(),
            },
        };
    }
    let start = offsets.source_to_builder(char_pos);
    let end = offsets.source_to_builder(para.next_grapheme_offset(char_pos));
    match first_rect(start, end) {
        Some(rect) => rect,
        None if char_pos == 0 => default_rect(),
        None => {
            // Fallback: use glyph position
            let pos = laid_out_para.get_glyph_position_at_coordinate((0.0, 0.0));
            Rect::from_xywh(pos.position as f32, 0.0, 1.0, laid_out_para.height())
        }
    }
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
            for builder_range in crate::shapes::HorizontalOffsets::new(para)
                .normal_selection_ranges(para, range_start, range_end)
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::{Paragraph, TextAlign, TextDirection, TextSpan};
    use skia_safe::textlayout::{
        FontCollection, Paragraph as SkiaParagraph, ParagraphBuilder, ParagraphStyle, TextStyle,
        TypefaceFontProvider,
    };
    use skia_safe::FontMgr;

    fn paragraph(text: &str) -> Paragraph {
        crate::globals::design_init();
        let span = TextSpan {
            text: text.to_string(),
            font_size: 16.0,
            ..TextSpan::default()
        };
        Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![span],
        )
    }

    fn laid_out(text: &str) -> SkiaParagraph {
        let mut provider = TypefaceFontProvider::new();
        let typeface = FontMgr::new()
            .new_from_data(
                skia_safe::Data::new_copy(include_bytes!("../fonts/sourcesanspro-regular.ttf")),
                None,
            )
            .expect("test font");
        provider.register_typeface(typeface, Some("test"));
        let mut fonts = FontCollection::new();
        fonts.set_default_font_manager(FontMgr::from(provider), None);
        let mut style = TextStyle::new();
        style.set_font_families(&["test"]);
        style.set_font_size(16.0);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        builder.push_style(&style);
        builder.add_text(text);
        let mut paragraph = builder.build();
        paragraph.layout(1000.0);
        paragraph
    }

    fn glyph_rect(laid_out: &SkiaParagraph, range: std::ops::Range<usize>) -> Rect {
        laid_out.get_rects_for_range(range, RectHeightStyle::Max, RectWidthStyle::Tight)[0].rect
    }

    #[test]
    fn caret_inside_the_text_covers_the_character_at_the_offset() {
        let laid_out = laid_out("abc");
        let caret = horizontal_caret_rect(&paragraph("abc"), &laid_out, 1);
        assert_eq!(caret, glyph_rect(&laid_out, 1..2));
    }

    #[test]
    fn caret_at_the_end_follows_the_last_character() {
        let laid_out = laid_out("abc");
        let caret = horizontal_caret_rect(&paragraph("abc"), &laid_out, 3);
        let last = glyph_rect(&laid_out, 2..3);
        assert_eq!(caret.left, last.right);
        assert_eq!(caret.width(), last.width());
    }

    #[test]
    fn caret_in_an_empty_paragraph_spans_its_height() {
        let laid_out = laid_out("");
        let caret = horizontal_caret_rect(&paragraph(""), &laid_out, 0);
        assert_eq!(caret, Rect::from_xywh(0.0, 0.0, 1.0, laid_out.height()));
    }
}
