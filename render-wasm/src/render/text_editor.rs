use crate::render::options::RenderOptions;
use crate::shapes::text_paragraph::LayoutParagraph;
use crate::shapes::text_vertical;
use crate::shapes::{vertical_align_offset, Shape, TextContent, TextPositionWithAffinity, Type};
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

fn paragraphs_vertical_offset(shape: &Shape, layout_paragraphs: &[&LayoutParagraph]) -> f32 {
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
    cursor: &TextPositionWithAffinity,
) -> Option<Rect> {
    let (layout, origin_x) = vertical_layout_for_shape(text_content, shape);
    let rect = text_vertical::caret_rect_with_affinity(
        &layout,
        cursor.paragraph,
        cursor.offset,
        cursor.is_upstream(),
    )?;
    Some(Rect::from_xywh(
        origin_x + rect.x(),
        rect.y(),
        rect.width(),
        rect.height(),
    ))
}

/// Vertical-layout boxes, shape-local, of the text between `start` and `end`
/// (paragraph, char offset).
fn vertical_range_rects(
    start: (usize, usize),
    end: (usize, usize),
    text_content: &TextContent,
    shape: &Shape,
) -> Vec<Rect> {
    let (start_paragraph, start_offset) = start;
    let (end_paragraph, end_offset) = end;
    let paragraphs = text_content.paragraphs();
    let (layout, origin_x) = vertical_layout_for_shape(text_content, shape);
    let mut rects = Vec::new();

    for (para_idx, paragraph) in paragraphs
        .iter()
        .enumerate()
        .take(end_paragraph + 1)
        .skip(start_paragraph)
    {
        let para_char_count: usize = paragraph
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
        return calculate_vertical_cursor_rect(text_content, shape, &cursor);
    }

    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();
    let laid_out_para = layout_paragraphs.get(cursor.paragraph)?;
    let y_offset = paragraphs_vertical_offset(shape, &layout_paragraphs)
        + layout_paragraphs[..cursor.paragraph]
            .iter()
            .map(|paragraph| paragraph.height())
            .sum::<f32>();
    let plans = text_content.horizontal_plans();
    let plan = plans.get(cursor.paragraph)?;
    let model = &paragraphs[cursor.paragraph];
    let rect = horizontal_caret_rect_with_offsets(
        model,
        laid_out_para,
        cursor.offset,
        cursor.is_upstream(),
        &plan.offsets,
    );
    Some(rect.with_offset((plan.x_offset(model, shape.selrect.width()), y_offset)))
}

/// The start (`before`) or the end of the whole text.
fn text_edge(text_content: &TextContent, before: bool) -> TextPositionWithAffinity {
    let paragraphs = text_content.paragraphs();
    if before || paragraphs.is_empty() {
        return TextPositionWithAffinity::new_downstream_affinity(0, 0);
    }
    let last = paragraphs.len() - 1;
    TextPositionWithAffinity::new_downstream_affinity(last, paragraph_chars(&paragraphs[last]))
}

fn paragraph_chars(paragraph: &crate::shapes::Paragraph) -> usize {
    paragraph
        .children()
        .iter()
        .map(|span| span.text.chars().count())
        .sum()
}

/// Laid-out horizontal paragraphs with their models and plans, or None
/// when the text has no layout for every paragraph.
fn horizontal_parts(
    text_content: &TextContent,
) -> Option<(
    Vec<&LayoutParagraph>,
    std::rc::Rc<Vec<crate::shapes::HorizontalParagraphPlan>>,
)> {
    let layout_paragraphs: Vec<&LayoutParagraph> =
        text_content.layout.paragraphs.iter().flatten().collect();
    (layout_paragraphs.len() == text_content.paragraphs().len())
        .then(|| (layout_paragraphs, text_content.horizontal_plans()))
}

/// Index of the line of `laid_out` at the paragraph-local `y`, or the
/// nearest one.
fn line_at(laid_out: &LayoutParagraph, y: f32) -> Option<usize> {
    let lines = laid_out.get_line_metrics();
    let distance = |line: &skia_safe::textlayout::LineMetrics| {
        let top = (line.baseline - line.ascent) as f32;
        let bottom = (line.baseline + line.descent) as f32;
        if y < top {
            top - y
        } else if y >= bottom {
            y - bottom
        } else {
            0.0
        }
    };
    (0..lines.len()).min_by(|a, b| distance(&lines[*a]).total_cmp(&distance(&lines[*b])))
}

/// Caret moved one visual line from `focus`: a line of horizontal text, up
/// when `before`, or a column of vertical text, to the right when `before`.
/// `goal` is the position along the line kept from the move before, if any.
/// Returns the new caret and the goal to keep; a move past the first or
/// last line goes to the start or end of the text. None without a layout.
pub(crate) fn caret_line_step(
    text_content: &TextContent,
    shape: &Shape,
    focus: &TextPositionWithAffinity,
    before: bool,
    goal: Option<f32>,
) -> Option<(TextPositionWithAffinity, f32)> {
    if text_content.is_vertical() {
        let (layout, _) = vertical_layout_for_shape(text_content, shape);
        let rect = text_vertical::caret_rect_with_affinity(
            &layout,
            focus.paragraph,
            focus.offset,
            focus.is_upstream(),
        )?;
        let goal = goal.unwrap_or(rect.top());
        let cursor = text_vertical::caret_column_step(
            &layout,
            focus.paragraph,
            focus.offset,
            focus.is_upstream(),
            before,
            goal,
        )
        .map_or_else(
            || text_edge(text_content, before),
            |(paragraph, offset)| {
                TextPositionWithAffinity::new_downstream_affinity(paragraph, offset)
            },
        );
        return Some((cursor, goal));
    }

    let (layout_paragraphs, plans) = horizontal_parts(text_content)?;
    let model = text_content.paragraphs().get(focus.paragraph)?;
    let laid_out = *layout_paragraphs.get(focus.paragraph)?;
    let plan = plans.get(focus.paragraph)?;
    let x_offset = plan.x_offset(model, shape.selrect.width());
    let rect = horizontal_caret_rect_with_offsets(
        model,
        laid_out,
        focus.offset,
        focus.is_upstream(),
        &plan.offsets,
    );
    let goal = goal.unwrap_or(rect.left() + x_offset);

    // Inside a warichu note, the other sub-line is one line away.
    if let Some((offset, upstream)) = crate::shapes::horizontal_warichu_step(
        model,
        laid_out,
        focus.offset,
        focus.is_upstream(),
        goal - x_offset,
        !before,
    ) {
        let cursor = if upstream {
            TextPositionWithAffinity::new_upstream_affinity(focus.paragraph, offset)
        } else {
            TextPositionWithAffinity::new_downstream_affinity(focus.paragraph, offset)
        };
        return Some((cursor, goal));
    }

    // Every line as (top, bottom) in content coordinates.
    let mut lines = Vec::new();
    let mut current = None;
    let mut paragraph_top = 0.0;
    for (index, paragraph) in layout_paragraphs.iter().enumerate() {
        let caret_line = (index == focus.paragraph)
            .then(|| line_at(paragraph, rect.center_y()))
            .flatten();
        for (line_index, line) in paragraph.get_line_metrics().iter().enumerate() {
            if caret_line == Some(line_index) {
                current = Some(lines.len());
            }
            lines.push((
                paragraph_top + (line.baseline - line.ascent) as f32,
                paragraph_top + (line.baseline + line.descent) as f32,
            ));
        }
        paragraph_top += paragraph.height();
    }
    let current = current?;
    let target = if before {
        current.checked_sub(1)
    } else {
        Some(current + 1).filter(|index| *index < lines.len())
    };
    let Some(target) = target else {
        return Some((text_edge(text_content, before), goal));
    };
    // Off-center, so a warichu note in that line takes the sub-line next
    // to the caret: the first going down, the second going up.
    let (top, bottom) = lines[target];
    let y = if before {
        top + (bottom - top) * 0.75
    } else {
        top + (bottom - top) * 0.25
    };
    let cursor = text_content.get_caret_position_from_shape_coords(
        &Point::new(goal, y),
        &shape.selrect,
        shape.vertical_align(),
    )?;
    Some((cursor, goal))
}

/// Caret at the start of the visual line or column holding `focus`, or at
/// its end when `end`. The end of a wrapped line or column is upstream, so
/// the caret stays on it.
pub(crate) fn caret_line_edge(
    text_content: &TextContent,
    shape: &Shape,
    focus: &TextPositionWithAffinity,
    end: bool,
) -> Option<TextPositionWithAffinity> {
    let model = text_content.paragraphs().get(focus.paragraph)?;
    let chars = paragraph_chars(model);
    let at = |offset: usize| {
        if end && offset < chars {
            TextPositionWithAffinity::new_upstream_affinity(focus.paragraph, offset)
        } else {
            TextPositionWithAffinity::new_downstream_affinity(focus.paragraph, offset)
        }
    };
    if text_content.is_vertical() {
        let (layout, _) = vertical_layout_for_shape(text_content, shape);
        let (_, offset) = text_vertical::caret_column_edge(
            &layout,
            focus.paragraph,
            focus.offset,
            focus.is_upstream(),
            end,
        )?;
        return Some(at(offset));
    }

    let (layout_paragraphs, plans) = horizontal_parts(text_content)?;
    let laid_out = *layout_paragraphs.get(focus.paragraph)?;
    let plan = plans.get(focus.paragraph)?;
    let rect = horizontal_caret_rect_with_offsets(
        model,
        laid_out,
        focus.offset,
        focus.is_upstream(),
        &plan.offsets,
    );
    let lines = laid_out.get_line_metrics();
    let index = line_at(laid_out, rect.center_y())?;
    let line = &lines[index];
    let offset = if !end {
        plan.offsets.builder_to_source(line.start_index)
    } else if index + 1 < lines.len() {
        plan.offsets
            .builder_to_source(line.end_excluding_whitespaces)
    } else {
        chars
    };
    Some(at(offset))
}

/// Caret rect at the source character offset `char_pos` of a laid-out
/// horizontal paragraph, in the paragraph's coordinates. It covers the
/// character at the offset (overtype carets use its width), or follows the
/// last character at the paragraph end.
#[cfg(test)]
fn horizontal_caret_rect(
    para: &crate::shapes::Paragraph,
    laid_out_para: &LayoutParagraph,
    char_pos: usize,
) -> Rect {
    horizontal_caret_rect_with_offsets(
        para,
        laid_out_para,
        char_pos,
        false,
        &crate::shapes::HorizontalOffsets::new(para),
    )
}

/// Caret rect at `char_pos` (see `horizontal_caret_rect`). An `upstream`
/// offset at the start of a wrapped line is drawn at the end of the line
/// before it.
fn horizontal_caret_rect_with_offsets(
    para: &crate::shapes::Paragraph,
    laid_out_para: &LayoutParagraph,
    char_pos: usize,
    upstream: bool,
    offsets: &crate::shapes::HorizontalOffsets,
) -> Rect {
    if let Some(rect) =
        crate::shapes::horizontal_warichu_caret_rect(para, laid_out_para, char_pos, upstream)
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
    let rect = first_rect(start, end);
    if upstream && char_pos > 0 {
        let previous_start = offsets.source_to_builder(para.prev_grapheme_offset(char_pos));
        if let (Some(previous), Some(rect)) = (first_rect(previous_start, start), rect) {
            if previous.top() < rect.top() {
                return Rect::from_xywh(
                    previous.right(),
                    previous.top(),
                    previous.width(),
                    previous.height(),
                );
            }
        }
    }
    match rect {
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

/// Box in page coordinates around the line boxes of the text between `start`
/// and `end` (paragraph, char offset), mapped through the shape transform: on
/// a rotated text it encloses the text as drawn. `None` when the range covers
/// no text.
pub(crate) fn range_page_bounds(
    start: (usize, usize),
    end: (usize, usize),
    text_content: &TextContent,
    shape: &Shape,
) -> Option<Rect> {
    let matrix = shape.get_matrix();
    let bounds = range_rects(start, end, text_content, shape)
        .iter()
        .map(|rect| matrix.map_rect(rect).0)
        .reduce(|mut bounds, rect| {
            bounds.join(rect);
            bounds
        })?;
    (!bounds.is_empty()).then_some(bounds)
}

/// Line boxes, shape-local, of the text between `start` and `end` (paragraph,
/// char offset).
fn range_rects(
    start: (usize, usize),
    end: (usize, usize),
    text_content: &TextContent,
    shape: &Shape,
) -> Vec<Rect> {
    if text_content.is_vertical() {
        return vertical_range_rects(start, end, text_content, shape);
    }

    let mut rects = Vec::new();
    let (start_paragraph, start_offset) = start;
    let (end_paragraph, end_offset) = end;
    let plans = text_content.horizontal_plans();

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
        let plan = &plans[para_idx];
        let x_offset = plan.x_offset(para, shape.selrect.width());
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
                    r.left() + x_offset,
                    y_offset + r.top(),
                    r.width(),
                    r.height(),
                ));
            }
            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            for builder_range in plan
                .offsets
                .normal_selection_ranges(para, range_start, range_end)
            {
                for text_box in laid_out_para.get_rects_for_range(
                    builder_range,
                    RectHeightStyle::Max,
                    RectWidthStyle::Tight,
                ) {
                    let r = text_box.rect;
                    rects.push(Rect::from_xywh(
                        r.left() + x_offset,
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
    use crate::globals::TestRenderResourcesGuard;
    use crate::render::RenderResources;
    use crate::shapes::{
        FontFamily, FontStyle, GrowType, Paragraph, TextAlign, TextDirection, TextSpan,
    };
    use crate::uuid::Uuid;
    use crate::wasm::text_editor::CursorDirection;
    use skia_safe::textlayout::{
        FontCollection, ParagraphBuilder, ParagraphStyle, TextStyle, TypefaceFontProvider,
    };
    use skia_safe::FontMgr;

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

    fn page_bounds_of(shape: &Shape, start: usize, end: usize) -> Option<Rect> {
        let Type::Text(content) = &shape.shape_type else {
            unreachable!()
        };
        range_page_bounds((0, start), (0, end), content, shape)
    }

    #[test]
    fn range_page_bounds_are_offset_by_the_shape_position() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let mut shape = text_shape("hello world", 500.0);
        let local = page_bounds_of(&shape, 6, 11).expect("world has text");
        shape.set_selrect(100.0, 50.0, 600.0, 150.0);

        let page = page_bounds_of(&shape, 6, 11).expect("world has text");

        assert!(
            (page.left() - (local.left() + 100.0)).abs() < 0.01,
            "{page:?}"
        );
        assert!((page.top() - (local.top() + 50.0)).abs() < 0.01, "{page:?}");
        assert!((page.width() - local.width()).abs() < 0.01, "{page:?}");
    }

    #[test]
    fn range_page_bounds_of_wrapped_text_cover_every_line() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        // Too narrow for both words on one line.
        let shape = text_shape("hello world", 45.0);

        let hello = page_bounds_of(&shape, 0, 5).expect("hello has text");
        let both = page_bounds_of(&shape, 0, 11).expect("the text has text");

        assert!(both.top() <= hello.top() + 0.01, "{both:?}");
        assert!(both.height() > hello.height() * 1.5, "{both:?}");
    }

    #[test]
    fn range_page_bounds_are_none_for_an_empty_range() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = text_shape("hello", 500.0);

        assert!(page_bounds_of(&shape, 2, 2).is_none());
    }

    #[test]
    fn range_origin_is_none_for_an_empty_range() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = text_shape("hello", 500.0);

        assert!(origin_of(&shape, 2, 2).is_none());
    }

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

    fn laid_out(text: &str) -> LayoutParagraph {
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
        let mut paragraph = LayoutParagraph::build(&mut builder);
        paragraph.layout(1000.0);
        paragraph
    }

    fn glyph_rect(laid_out: &LayoutParagraph, range: std::ops::Range<usize>) -> Rect {
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
    #[test]
    fn narrow_cjk_layout_shares_alignment_with_carets_selection_hit_testing_and_exports() {
        use crate::shapes::calculate_text_layout_data;
        use crate::shapes::text_paths::TextPaths;
        use crate::shapes::text_vertical::test_support::provider;
        use crate::shapes::{GrowType, TextPositionWithAffinity, VerticalAlign};
        crate::globals::design_init();
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let mut fonts = resources.fonts.font_collection().clone();
        fonts.set_asset_font_manager(Some(
            provider(include_bytes!("../fonts/cjk-locl-test.ttf")).into(),
        ));
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        for align in [
            TextAlign::Left,
            TextAlign::Right,
            TextAlign::Center,
            TextAlign::Justify,
        ] {
            for grow in [GrowType::Fixed, GrowType::AutoHeight] {
                let spans = ["文文", "文", "。", "文文"]
                    .iter()
                    .map(|text| TextSpan {
                        text: text.to_string(),
                        font_size: 20.0,
                        line_height: 1.0,
                        ..TextSpan::default()
                    })
                    .collect();
                let mut model =
                    Paragraph::new(align, TextDirection::LTR, None, None, 1.0, 0.0, spans);
                model.set_language("zh-Hant-TW".to_owned());
                let mut content =
                    TextContent::new(crate::math::Rect::from_xywh(0.0, 0.0, 20.0, 300.0), grow);
                content.add_paragraph(model.clone());
                content.add_paragraph(model);
                let mut shape = Shape::new(crate::Uuid::nil());
                shape.set_selrect(0.0, 0.0, 20.0, 300.0);
                content.update_layout(shape.selrect);
                let plans = content.horizontal_plans();
                let mut editor = TextEditorState::new();
                let data = calculate_text_layout_data(
                    &shape,
                    &content,
                    &mut content.paragraph_builder_group_from_text(None),
                    false,
                );
                let mut paragraph_y = 0.0;
                for paragraph_index in 0..2 {
                    let laid_out = &content.layout.paragraphs[paragraph_index][0];
                    let plan = &plans[paragraph_index];
                    let x_offset = plan.x_offset(&content.paragraphs()[paragraph_index], 20.0);
                    let lines = laid_out.get_line_metrics();
                    assert_eq!(lines.len(), 5, "{align:?} {grow:?}");
                    let atomic_left = lines[2].left as f32 + x_offset;
                    let expected_left = match align {
                        TextAlign::Right => -20.0,
                        TextAlign::Center => -10.0,
                        _ => 0.0,
                    };
                    assert!(
                        (atomic_left - expected_left).abs() < 0.02,
                        "{align:?}: {atomic_left}"
                    );
                    assert!(
                        (lines[0].left as f32 + x_offset).abs() < 0.02,
                        "ordinary text stays within its box"
                    );
                    for (offset, line_index) in [0, 1, 2, 2, 3, 4].into_iter().enumerate() {
                        let position = TextPositionWithAffinity::new_downstream_affinity(
                            paragraph_index,
                            offset,
                        );
                        editor.selection.set_caret(position);
                        let caret = cursor_rect(&editor, &content, &shape).expect("caret");
                        let line_top = paragraph_y
                            + lines[..line_index]
                                .iter()
                                .map(|line| line.height as f32)
                                .sum::<f32>();
                        assert!(
                            (caret.top() - line_top).abs() < 0.1,
                            "caret {offset} in {align:?}: {caret:?}"
                        );
                        let hit = content
                            .get_caret_position_from_shape_coords(
                                &crate::math::Point::new(
                                    caret.left() + caret.width() * 0.1,
                                    caret.center_y(),
                                ),
                                &shape.selrect,
                                VerticalAlign::Top,
                            )
                            .expect("hit");
                        assert_eq!(
                            (hit.paragraph, hit.offset),
                            (paragraph_index, offset),
                            "{align:?}, caret={caret:?}"
                        );
                    }
                    editor.selection.anchor =
                        TextPositionWithAffinity::new_downstream_affinity(paragraph_index, 2);
                    editor.selection.focus =
                        TextPositionWithAffinity::new_downstream_affinity(paragraph_index, 4);
                    let selected = calculate_selection_rects(&editor.selection, &content, &shape);
                    assert!(!selected.is_empty());
                    assert!(selected.iter().all(|rect| (rect.top()
                        - paragraph_y
                        - lines[..2]
                            .iter()
                            .map(|line| line.height as f32)
                            .sum::<f32>())
                    .abs()
                        < 0.1));
                    assert!((selected.iter().map(|r| r.width()).sum::<f32>() - 40.0).abs() < 0.1);
                    let exported = &data.paragraphs[paragraph_index];
                    assert!((exported.x - x_offset).abs() < 0.01);
                    assert!((exported.y - paragraph_y).abs() < 0.01);
                    assert_eq!(exported.paragraph.get_line_metrics().len(), 5);
                    paragraph_y += laid_out.height();
                }
                let paths = TextPaths::new(content.clone()).get_paths(VerticalAlign::Top);
                assert!(!paths.is_empty());
                let mut bounds = skia_safe::Rect::new_empty();
                for path in paths {
                    bounds.join(path.bounds());
                }
                assert!(
                    bounds.height() > paragraph_y - 30.0,
                    "paths include both paragraphs"
                );
                assert!(
                    bounds.width() > 20.0,
                    "paths include the overflowing punctuation unit: {align:?} {bounds:?}"
                );
                assert!(
                    content.get_height(160.0) < paragraph_y / 2.0,
                    "prospective width uses a fresh plan"
                );
                let before: Vec<_> = plans.iter().map(|plan| plan.texts.clone()).collect();
                content.update_layout(crate::math::Rect::from_xywh(0.0, 0.0, 160.0, 300.0));
                assert!(content
                    .layout
                    .paragraphs
                    .iter()
                    .all(|group| group[0].get_line_metrics().len() == 1));
                assert!(content.horizontal_plans().iter().all(|plan| !plan
                    .texts
                    .concat()
                    .contains(crate::shapes::line_breaks::FORCED_LINE_BREAK)));
                content.update_layout(shape.selrect);
                assert_eq!(
                    content
                        .horizontal_plans()
                        .iter()
                        .map(|plan| plan.texts.clone())
                        .collect::<Vec<_>>(),
                    before,
                    "narrow/wide/narrow restores the plan"
                );
                assert_eq!(
                    content.paragraphs()[0]
                        .children()
                        .iter()
                        .map(|s| s.text.as_str())
                        .collect::<String>(),
                    "文文文。文文"
                );
            }
        }
    }
    #[test]
    fn narrow_cjk_fractional_strokes_share_the_fill_breaks_and_advances() {
        use crate::shapes::text_vertical::test_support::provider;
        use crate::shapes::{GrowType, Stroke, StrokeStyle};
        crate::globals::design_init();
        let mut resources =
            crate::render::RenderResources::try_new_headless().expect("headless resources");
        let mut fonts = resources.fonts.font_collection().clone();
        fonts.set_asset_font_manager(Some(
            provider(include_bytes!("../fonts/cjk-locl-test.ttf")).into(),
        ));
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let spans = ["文文", "文", "。", "文文"]
            .iter()
            .map(|text| TextSpan {
                text: text.to_string(),
                font_size: 20.125,
                line_height: 1.0,
                ..TextSpan::default()
            })
            .collect();
        let mut model = Paragraph::new(
            TextAlign::Justify,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            spans,
        );
        model.set_language("zh-Hant-TW".to_owned());
        let mut content = TextContent::new(
            crate::math::Rect::from_xywh(0.0, 0.0, 32.25, 300.0),
            GrowType::Fixed,
        );
        content.add_paragraph(model);
        content.update_layout(content.bounds());
        let stroke = Stroke::new_center_stroke(1.0, StrokeStyle::Solid, None, None, None, None);
        let (mut builders, _) = crate::render::text::stroke_paragraph_builder_group_from_text(
            &content,
            &stroke,
            &content.bounds(),
            None,
        );
        assert!(!builders[0].is_empty());
        let mut stroked = builders[0][0].build();
        stroked.layout(content.horizontal_plans()[0].layout_width(32.25));
        let fill_lines = content.layout.paragraphs[0][0].get_line_metrics();
        let stroke_lines = stroked.get_line_metrics();
        assert_eq!(fill_lines.len(), stroke_lines.len());
        for (fill, stroke) in fill_lines.iter().zip(&stroke_lines) {
            assert_eq!(
                (fill.start_index, fill.end_index),
                (stroke.start_index, stroke.end_index)
            );
            assert!((fill.width - stroke.width).abs() < 0.01);
            assert!((fill.left - stroke.left).abs() < 0.01);
        }
    }

    /// Family of the Japanese test spans.
    fn japanese() -> FontFamily {
        FontFamily::new(Uuid::nil(), 700, FontStyle::Normal)
    }

    /// Headless resources with a Noto Sans JP subset as `japanese()`. Its
    /// notdef glyph is 1em wide, so every character takes a full em.
    fn japanese_resources() -> RenderResources {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        resources
            .fonts
            .add(
                japanese(),
                crate::shapes::text_vertical::test_support::VPAL_TEST_FONT,
                false,
                false,
            )
            .expect("test font");
        resources
    }

    /// A laid-out fixed Japanese text `width` by `height` with one paragraph
    /// of `spans` (text, warichu), set vertically when `vertical`. Lines are
    /// taller than the characters, as with the default line height.
    fn japanese_shape(spans: &[(&str, bool)], width: f32, height: f32, vertical: bool) -> Shape {
        crate::globals::design_init();
        let spans = spans
            .iter()
            .map(|(text, warichu)| TextSpan {
                text: text.to_string(),
                font_family: japanese(),
                font_size: 20.0,
                line_height: 1.4,
                warichu: *warichu,
                ..TextSpan::default()
            })
            .collect();
        let mut paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            spans,
        );
        paragraph.set_language("ja".to_owned());
        if vertical {
            paragraph.set_writing_mode(crate::shapes::WritingMode::VerticalRl);
        }
        let mut content =
            TextContent::new(Rect::from_xywh(0.0, 0.0, width, height), GrowType::Fixed);
        content.add_paragraph(paragraph);
        content.update_layout(Rect::from_xywh(0.0, 0.0, width, height));
        let mut shape = Shape::new(Uuid::nil());
        shape.set_shape_type(Type::Text(content));
        shape.set_selrect(0.0, 0.0, width, height);
        shape
    }

    fn content_of(shape: &Shape) -> &TextContent {
        let Type::Text(content) = &shape.shape_type else {
            unreachable!()
        };
        content
    }

    /// Caret offset after each key, starting at `start`.
    fn walk(shape: &Shape, start: usize, keys: Vec<CursorDirection>) -> Vec<usize> {
        let content = content_of(shape);
        let mut editor = TextEditorState::new();
        editor
            .selection
            .set_caret(TextPositionWithAffinity::new_downstream_affinity(0, start));
        keys.into_iter()
            .map(|key| {
                editor.move_cursor(content, shape, key, false, false);
                editor.selection.focus.offset
            })
            .collect()
    }

    fn caret_at(shape: &Shape, position: TextPositionWithAffinity) -> Rect {
        let mut editor = TextEditorState::new();
        editor.selection.set_caret(position);
        cursor_rect(&editor, content_of(shape), shape).expect("caret")
    }

    #[test]
    fn up_and_down_move_by_line_and_keep_the_caret_position() {
        let mut resources = japanese_resources();
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = japanese_shape(
            &[("あいうえおかきくけこさしすせそ", false)],
            60.0,
            200.0,
            false,
        );
        let lines = content_of(&shape).layout.paragraphs[0][0].get_line_metrics();
        assert!(lines.len() >= 3, "the text wraps into lines");
        let per_line = lines[0].end_index - lines[0].start_index;
        use CursorDirection::{LineAfter as Down, LineBefore as Up};
        assert_eq!(
            walk(&shape, 1, vec![Down, Down, Up, Up]),
            vec![1 + per_line, 1 + 2 * per_line, 1 + per_line, 1]
        );
        assert_eq!(walk(&shape, 1, vec![Up]), vec![0], "up from the first line");
    }

    #[test]
    fn home_and_end_go_to_the_edges_of_the_visual_line() {
        let mut resources = japanese_resources();
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = japanese_shape(
            &[("あいうえおかきくけこさしすせそ", false)],
            60.0,
            200.0,
            false,
        );
        let lines = content_of(&shape).layout.paragraphs[0][0].get_line_metrics();
        let per_line = lines[0].end_index - lines[0].start_index;
        let start = per_line + 1;
        assert_eq!(
            walk(&shape, start, vec![CursorDirection::LineStart]),
            vec![per_line]
        );
        assert_eq!(
            walk(
                &shape,
                start,
                vec![CursorDirection::LineEnd, CursorDirection::LineEnd]
            ),
            vec![2 * per_line, 2 * per_line],
            "End stays at the end of the line"
        );
        let end = caret_at(
            &shape,
            TextPositionWithAffinity::new_upstream_affinity(0, 2 * per_line),
        );
        let middle = caret_at(
            &shape,
            TextPositionWithAffinity::new_downstream_affinity(0, start),
        );
        assert!(
            (end.top() - middle.top()).abs() < 0.01 && end.left() > middle.left(),
            "the line end is drawn on its own line: {end:?} vs {middle:?}"
        );
    }

    const NOTE_SPANS: [(&str, bool); 3] = [
        ("東京の本社（", false),
        ("二〇二六年に移転した新しい建物", true),
        ("あ）で会議を行う。", false),
    ];

    #[test]
    fn up_and_down_step_between_the_halves_of_a_warichu_note() {
        let mut resources = japanese_resources();
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = japanese_shape(&NOTE_SPANS, 400.0, 200.0, false);
        use CursorDirection::{LineAfter as Down, LineBefore as Up};
        // 6..14 is the first half of the note, 14..21 the second.
        let steps = walk(&shape, 8, vec![Down, Up]);
        assert!(
            (14..=21).contains(&steps[0]),
            "down from the first half lands in the second: {steps:?}"
        );
        assert_eq!(steps[1], 8, "and up comes back");
    }

    #[test]
    fn clicks_on_a_warichu_note_keep_to_the_half_under_the_pointer() {
        let mut resources = japanese_resources();
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = japanese_shape(&NOTE_SPANS, 400.0, 200.0, false);
        let content = content_of(&shape);
        let hit = |x: f32, y: f32| {
            content
                .get_caret_position_from_shape_coords(
                    &Point::new(x, y),
                    &shape.selrect,
                    crate::shapes::VerticalAlign::Top,
                )
                .expect("hit")
        };
        let first = caret_at(
            &shape,
            TextPositionWithAffinity::new_downstream_affinity(0, 7),
        );
        let lines = content.layout.paragraphs[0][0].get_line_metrics();
        let line_top = (lines[0].baseline - lines[0].ascent) as f32;
        assert!(line_top < first.top(), "the line is taller than the note");
        let above = hit(first.center_x(), (line_top + first.top()) / 2.0);
        assert!(
            (6..14).contains(&above.offset),
            "above the first half picks it, got {}",
            above.offset
        );

        let last = caret_at(
            &shape,
            TextPositionWithAffinity::new_downstream_affinity(0, 13),
        );
        let split = hit(last.right() - 0.1, last.center_y());
        assert_eq!(split.offset, 14);
        assert!(split.is_upstream(), "the end of the first half is upstream");
        let caret = caret_at(&shape, split);
        assert!(
            (caret.top() - last.top()).abs() < 0.01 && caret.left() >= last.right() - 0.01,
            "drawn after the half's last character: {caret:?} vs {last:?}"
        );
    }

    #[test]
    fn left_and_right_move_across_vertical_columns() {
        let mut resources = japanese_resources();
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let shape = japanese_shape(
            &[("あいうえおかきくけこさしすせそ", false)],
            200.0,
            60.0,
            true,
        );
        let layout = content_of(&shape).vertical_layout(&shape.selrect);
        assert!(layout.columns.len() >= 3, "the text wraps into columns");
        let per_column = (0..15)
            .find(|offset| {
                text_vertical::caret_rect_with_affinity(&layout, 0, *offset, false)
                    .expect("caret")
                    .left()
                    < layout.columns[0].x
            })
            .expect("a second column");
        use CursorDirection::{Backward as Left, Forward as Right};
        assert_eq!(
            walk(&shape, 1, vec![Left, Left, Right, Right]),
            vec![1 + per_column, 1 + 2 * per_column, 1 + per_column, 1]
        );
        assert_eq!(
            walk(&shape, per_column + 1, vec![CursorDirection::LineStart]),
            vec![per_column]
        );
        assert_eq!(
            walk(&shape, per_column + 1, vec![CursorDirection::LineEnd]),
            vec![2 * per_column]
        );
    }
}
