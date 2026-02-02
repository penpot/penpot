use std::io::Cursor;

use crate::math::{Matrix, Point, Rect};
use crate::mem;
use crate::shapes::{Paragraph, Shape, TextContent, Type, VerticalAlign};
use crate::state::{TextCursor, TextSelection};
use crate::utils::uuid_from_u32_quartet;
use crate::utils::uuid_to_u32_quartet;
use crate::{with_state, with_state_mut, STATE};

#[derive(Debug, Copy, Clone, PartialEq)]
#[repr(u8)]
pub enum CursorDirection {
    Backward,
    Forward,
    LineBefore,
    LineAfter,
    LineStart,
    LineEnd,
}

// ============================================================================
// STATE MANAGEMENT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_start(a: u32, b: u32, c: u32, d: u32) -> bool {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);

        let Some(shape) = state.shapes.get(&shape_id) else {
            return false;
        };

        if !matches!(shape.shape_type, Type::Text(_)) {
            return false;
        }

        state.text_editor_state.start(shape_id);
        true
    })
}

#[no_mangle]
pub extern "C" fn text_editor_stop() {
    with_state_mut!(state, {
        state.text_editor_state.stop();
    });
}

#[no_mangle]
pub extern "C" fn text_editor_is_active() -> bool {
    with_state!(state, { state.text_editor_state.is_active })
}

#[no_mangle]
pub extern "C" fn text_editor_get_active_shape_id(buffer_ptr: *mut u32) {
    with_state!(state, {
        if let Some(shape_id) = state.text_editor_state.active_shape_id {
            let (a, b, c, d) = uuid_to_u32_quartet(&shape_id);
            unsafe {
                *buffer_ptr = a;
                *buffer_ptr.add(1) = b;
                *buffer_ptr.add(2) = c;
                *buffer_ptr.add(3) = d;
            }
        }
    })
}

#[no_mangle]
pub extern "C" fn text_editor_select_all() {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };

        let paragraphs = text_content.paragraphs();
        if paragraphs.is_empty() {
            return;
        }

        let last_para_idx = paragraphs.len() - 1;
        let last_para = &paragraphs[last_para_idx];
        let total_chars: usize = last_para
            .children()
            .iter()
            .map(|span| span.text.chars().count())
            .sum();

        use crate::state::TextCursor;
        state.text_editor_state.selection.anchor = TextCursor::new(0, 0);
        state.text_editor_state.selection.focus = TextCursor::new(last_para_idx, total_chars);
        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::SelectionChanged);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_poll_event() -> u8 {
    with_state_mut!(state, { state.text_editor_state.poll_event() as u8 })
}

// ============================================================================
// SELECTION MANAGEMENT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_set_cursor_from_point(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let (shape_matrix, view_matrix, selrect, vertical_align) = {
            let Some(shape) = state.shapes.get(&shape_id) else {
                return;
            };
            (
                shape.get_concatenated_matrix(&state.shapes),
                state.render_state.viewbox.get_matrix(),
                shape.selrect(),
                shape.vertical_align(),
            )
        };

        let Some(inv_view_matrix) = view_matrix.invert() else {
            return;
        };

        let Some(inv_shape_matrix) = shape_matrix.invert() else {
            return;
        };

        let mut matrix = Matrix::new_identity();
        matrix.post_concat(&inv_view_matrix);
        matrix.post_concat(&inv_shape_matrix);

        let mapped_point = matrix.map_point(Point::new(x, y));

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        if text_content.layout.paragraphs.is_empty() && !text_content.paragraphs().is_empty() {
            let bounds = text_content.bounds;
            text_content.update_layout(bounds);
        }

        // Calculate vertical alignment offset (same as in render/text_editor.rs)
        let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();
        let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
        let vertical_offset = match vertical_align {
            crate::shapes::VerticalAlign::Center => (selrect.height() - total_height) / 2.0,
            crate::shapes::VerticalAlign::Bottom => selrect.height() - total_height,
            _ => 0.0,
        };

        // Adjust point: subtract selrect offset and vertical alignment
        // The text layout expects coordinates where (0, 0) is the top-left of the text content
        let adjusted_point = Point::new(
            mapped_point.x - selrect.x(),
            mapped_point.y - selrect.y() - vertical_offset,
        );

        if let Some(position) = text_content.get_caret_position_at(&adjusted_point) {
            state.text_editor_state.set_caret_from_position(position);
        }
    });
}

#[no_mangle]
pub extern "C" fn text_editor_extend_selection_to_point(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let (shape_matrix, view_matrix, selrect, vertical_align) = {
            let Some(shape) = state.shapes.get(&shape_id) else {
                return;
            };
            (
                shape.get_concatenated_matrix(&state.shapes),
                state.render_state.viewbox.get_matrix(),
                shape.selrect(),
                shape.vertical_align(),
            )
        };

        let Some(inv_view_matrix) = view_matrix.invert() else {
            return;
        };

        let Some(inv_shape_matrix) = shape_matrix.invert() else {
            return;
        };

        let mut matrix = Matrix::new_identity();
        matrix.post_concat(&inv_view_matrix);
        matrix.post_concat(&inv_shape_matrix);

        let mapped_point = matrix.map_point(Point::new(x, y));

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        if text_content.layout.paragraphs.is_empty() && !text_content.paragraphs().is_empty() {
            let bounds = text_content.bounds;
            text_content.update_layout(bounds);
        }

        // Calculate vertical alignment offset (same as in render/text_editor.rs)
        let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();
        let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
        let vertical_offset = match vertical_align {
            crate::shapes::VerticalAlign::Center => (selrect.height() - total_height) / 2.0,
            crate::shapes::VerticalAlign::Bottom => selrect.height() - total_height,
            _ => 0.0,
        };

        // Adjust point: subtract selrect offset and vertical alignment
        let adjusted_point = Point::new(
            mapped_point.x - selrect.x(),
            mapped_point.y - selrect.y() - vertical_offset,
        );

        if let Some(position) = text_content.get_caret_position_at(&adjusted_point) {
            state
                .text_editor_state
                .extend_selection_from_position(position);
        }
    });
}

// ============================================================================
// TEXT OPERATIONS
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_insert_text() {
    let bytes = crate::mem::bytes();
    let text = match String::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return,
    };

    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        let selection = state.text_editor_state.selection;

        if selection.is_selection() {
            delete_selection_range(text_content, &selection);
            let start = selection.start();
            state.text_editor_state.selection.set_caret(start);
        }

        let cursor = state.text_editor_state.selection.focus;

        if let Some(new_offset) = insert_text_at_cursor(text_content, &cursor, &text) {
            let new_cursor = TextCursor::new(cursor.paragraph, new_offset);
            state.text_editor_state.selection.set_caret(new_cursor);
        }

        text_content.layout.paragraphs.clear();
        text_content.layout.paragraph_builders.clear();

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::ContentChanged);
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::NeedsLayout);

        state.render_state.mark_touched(shape_id);
    });

    crate::mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn text_editor_delete_backward() {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        let selection = state.text_editor_state.selection;

        if selection.is_selection() {
            delete_selection_range(text_content, &selection);
            let start = selection.start();
            let clamped = clamp_cursor(start, text_content.paragraphs());
            state.text_editor_state.selection.set_caret(clamped);
        } else {
            let cursor = selection.focus;
            if let Some(new_cursor) = delete_char_before(text_content, &cursor) {
                state.text_editor_state.selection.set_caret(new_cursor);
            }
        }

        text_content.layout.paragraphs.clear();
        text_content.layout.paragraph_builders.clear();

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::ContentChanged);
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::NeedsLayout);

        state.render_state.mark_touched(shape_id);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_delete_forward() {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        let selection = state.text_editor_state.selection;

        if selection.is_selection() {
            delete_selection_range(text_content, &selection);
            let start = selection.start();
            let clamped = clamp_cursor(start, text_content.paragraphs());
            state.text_editor_state.selection.set_caret(clamped);
        } else {
            let cursor = selection.focus;
            delete_char_after(text_content, &cursor);
            let clamped = clamp_cursor(cursor, text_content.paragraphs());
            state.text_editor_state.selection.set_caret(clamped);
        }

        text_content.layout.paragraphs.clear();
        text_content.layout.paragraph_builders.clear();

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::ContentChanged);
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::NeedsLayout);

        state.render_state.mark_touched(shape_id);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_insert_paragraph() {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return;
        };

        let selection = state.text_editor_state.selection;

        if selection.is_selection() {
            delete_selection_range(text_content, &selection);
            let start = selection.start();
            state.text_editor_state.selection.set_caret(start);
        }

        let cursor = state.text_editor_state.selection.focus;

        if split_paragraph_at_cursor(text_content, &cursor) {
            let new_cursor = TextCursor::new(cursor.paragraph + 1, 0);
            state.text_editor_state.selection.set_caret(new_cursor);
        }

        text_content.layout.paragraphs.clear();
        text_content.layout.paragraph_builders.clear();

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::ContentChanged);
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::NeedsLayout);

        state.render_state.mark_touched(shape_id);
    });
}

// ============================================================================
// NAVIGATION
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_move_cursor(direction: CursorDirection, extend_selection: bool) {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };

        let paragraphs = text_content.paragraphs();
        if paragraphs.is_empty() {
            return;
        }

        let current = state.text_editor_state.selection.focus;

        let new_cursor = match direction {
            CursorDirection::Backward => move_cursor_backward(&current, paragraphs),
            CursorDirection::Forward => move_cursor_forward(&current, paragraphs),
            CursorDirection::LineBefore => move_cursor_up(&current, paragraphs, text_content, shape),
            CursorDirection::LineAfter => move_cursor_down(&current, paragraphs, text_content, shape),
            CursorDirection::LineStart => move_cursor_line_start(&current, paragraphs),
            CursorDirection::LineEnd => move_cursor_line_end(&current, paragraphs),
            _ => current,
        };

        if extend_selection {
            state.text_editor_state.selection.extend_to(new_cursor);
        } else {
            state.text_editor_state.selection.set_caret(new_cursor);
        }

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(crate::state::EditorEvent::SelectionChanged);
    });
}

// ============================================================================
// RENDERING & EXPORT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_get_cursor_rect() -> *mut u8 {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active || !state.text_editor_state.cursor_visible {
            return std::ptr::null_mut();
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return std::ptr::null_mut();
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return std::ptr::null_mut();
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return std::ptr::null_mut();
        };

        let cursor = &state.text_editor_state.selection.focus;

        if let Some(rect) = get_cursor_rect(text_content, cursor, shape) {
            let mut bytes = vec![0u8; 16];
            bytes[0..4].copy_from_slice(&rect.left().to_le_bytes());
            bytes[4..8].copy_from_slice(&rect.top().to_le_bytes());
            bytes[8..12].copy_from_slice(&rect.width().to_le_bytes());
            bytes[12..16].copy_from_slice(&rect.height().to_le_bytes());
            return mem::write_bytes(bytes);
        }

        std::ptr::null_mut()
    })
}

#[no_mangle]
pub extern "C" fn text_editor_get_selection_rects() -> *mut u8 {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return std::ptr::null_mut();
        }

        if state.text_editor_state.selection.is_collapsed() {
            return std::ptr::null_mut();
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return std::ptr::null_mut();
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return std::ptr::null_mut();
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return std::ptr::null_mut();
        };

        let selection = &state.text_editor_state.selection;
        let rects = get_selection_rects(text_content, selection, shape);

        if rects.is_empty() {
            return std::ptr::null_mut();
        }

        let mut bytes = Vec::with_capacity(4 + rects.len() * 16);
        bytes.extend_from_slice(&(rects.len() as u32).to_le_bytes());
        for rect in rects {
            bytes.extend_from_slice(&rect.left().to_le_bytes());
            bytes.extend_from_slice(&rect.top().to_le_bytes());
            bytes.extend_from_slice(&rect.width().to_le_bytes());
            bytes.extend_from_slice(&rect.height().to_le_bytes());
        }
        mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_update_blink(timestamp_ms: f64) {
    with_state_mut!(state, {
        state.text_editor_state.update_blink(timestamp_ms);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_render_overlay() {
    with_state_mut!(state, {
        if !state.text_editor_state.is_active {
            return;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };

        if let Some(shape) = state.shapes.get(&shape_id) {
            if let Type::Text(text_content) = &shape.shape_type {
                if text_content.needs_update_layout() {
                    let selrect = shape.selrect();
                    if let Some(shape) = state.shapes.get_mut(&shape_id) {
                        if let Type::Text(text_content) = &mut shape.shape_type {
                            text_content.update_layout(selrect);
                        }
                    }
                }
            }
        }

        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };

        let transform = shape.get_concatenated_matrix(&state.shapes);

        use crate::render::text_editor as te_render;
        use crate::render::SurfaceId;

        let canvas = state.render_state.surfaces.canvas(SurfaceId::Target);

        canvas.save();
        let viewbox = state.render_state.viewbox;
        let zoom = viewbox.zoom * state.render_state.options.dpr();
        canvas.scale((zoom, zoom));
        canvas.translate((-viewbox.area.left, -viewbox.area.top));

        te_render::render_overlay(canvas, &state.text_editor_state, shape, &transform);

        canvas.restore();
        state.render_state.flush_and_submit();
    });
}

#[no_mangle]
pub extern "C" fn text_editor_export_content() -> *mut u8 {
    with_state!(state, {
        if !state.text_editor_state.is_active {
            return std::ptr::null_mut();
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return std::ptr::null_mut();
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return std::ptr::null_mut();
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return std::ptr::null_mut();
        };

        let mut json_parts: Vec<String> = Vec::new();
        for para in text_content.paragraphs() {
            let mut span_parts: Vec<String> = Vec::new();
            for span in para.children() {
                let escaped_text = span
                    .text
                    .replace('\\', "\\\\")
                    .replace('"', "\\\"")
                    .replace('\n', "\\n")
                    .replace('\r', "\\r")
                    .replace('\t', "\\t");
                span_parts.push(format!("\"{}\"", escaped_text));
            }
            json_parts.push(format!("[{}]", span_parts.join(",")));
        }
        let json = format!("[{}]", json_parts.join(","));

        let mut bytes = json.into_bytes();
        bytes.push(0);
        crate::mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_export_selection() -> *mut u8 {
    use std::ptr;
    with_state!(state, {
        if !state.text_editor_state.is_active {
            return ptr::null_mut();
        }
        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return ptr::null_mut();
        };
        let Some(shape) = state.shapes.get(&shape_id) else {
            return ptr::null_mut();
        };
        let Type::Text(text_content) = &shape.shape_type else {
            return ptr::null_mut();
        };
        let selection = &state.text_editor_state.selection;
        let start = selection.start();
        let end = selection.end();
        let paragraphs = text_content.paragraphs();
        let mut result = String::new();
        let end_paragraph = end.paragraph.min(paragraphs.len().saturating_sub(1)) + 1;
        for (para_idx, _) in paragraphs
            .iter()
            .enumerate()
            .take(end_paragraph)
            .skip(start.paragraph)
        {
            let para = &paragraphs[para_idx];
            let mut para_text = String::new();
            let para_char_count: usize = para
                .children()
                .iter()
                .map(|span| span.text.chars().count())
                .sum();
            let range_start = if para_idx == start.paragraph {
                start.char_offset
            } else {
                0
            };
            let range_end = if para_idx == end.paragraph {
                end.char_offset
            } else {
                para_char_count
            };
            if range_start < range_end {
                let mut char_pos = 0;
                for span in para.children() {
                    let span_len = span.text.chars().count();
                    let span_start = char_pos;
                    let span_end = char_pos + span_len;
                    let sel_start = range_start.max(span_start);
                    let sel_end = range_end.min(span_end);
                    if sel_start < sel_end {
                        let rel_start = sel_start - span_start;
                        let rel_end = sel_end - span_start;
                        let text: String = span
                            .text
                            .chars()
                            .skip(rel_start)
                            .take(rel_end - rel_start)
                            .collect();
                        para_text.push_str(&text);
                    }
                    char_pos += span_len;
                }
            }
            if !para_text.is_empty() {
                if !result.is_empty() {
                    result.push('\n');
                }
                result.push_str(&para_text);
            }
        }
        let mut bytes = result.into_bytes();
        bytes.push(0);
        crate::mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_get_selection(buffer_ptr: *mut u32) -> u32 {
    with_state!(state, {
        if !state.text_editor_state.is_active {
            return 0;
        }
        let sel = &state.text_editor_state.selection;
        unsafe {
            *buffer_ptr = sel.anchor.paragraph as u32;
            *buffer_ptr.add(1) = sel.anchor.char_offset as u32;
            *buffer_ptr.add(2) = sel.focus.paragraph as u32;
            *buffer_ptr.add(3) = sel.focus.char_offset as u32;
        }
        1
    })
}

// ============================================================================
// HELPERS: Cursor & Selection
// ============================================================================

fn get_cursor_rect(text_content: &TextContent, cursor: &TextCursor, shape: &Shape) -> Option<Rect> {
    let paragraphs = text_content.paragraphs();
    if cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();

    let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
    let valign_offset = match shape.vertical_align() {
        VerticalAlign::Center => (shape.selrect().height() - total_height) / 2.0,
        VerticalAlign::Bottom => shape.selrect().height() - total_height,
        _ => 0.0,
    };

    let mut y_offset = valign_offset;
    for (idx, laid_out_para) in layout_paragraphs.iter().enumerate() {
        if idx == cursor.paragraph {
            let char_pos = cursor.char_offset;

            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            let rects = laid_out_para.get_rects_for_range(
                char_pos..char_pos,
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            );

            let (x, height) = if !rects.is_empty() {
                (rects[0].rect.left(), rects[0].rect.height())
            } else {
                let pos = laid_out_para.get_glyph_position_at_coordinate((0.0, 0.0));
                let height = laid_out_para.height();
                (pos.position as f32, height)
            };

            let cursor_width = 2.0;
            let selrect = shape.selrect();
            let base_x = selrect.x();
            let base_y = selrect.y() + y_offset;

            return Some(Rect::from_xywh(base_x + x, base_y, cursor_width, height));
        }
        y_offset += laid_out_para.height();
    }

    None
}

/// Get selection rectangles for a given selection.
fn get_selection_rects(
    text_content: &TextContent,
    selection: &TextSelection,
    shape: &Shape,
) -> Vec<Rect> {
    let mut rects = Vec::new();

    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs();
    let layout_paragraphs: Vec<_> = text_content.layout.paragraphs.iter().flatten().collect();

    let selrect = shape.selrect();

    let total_height: f32 = layout_paragraphs.iter().map(|p| p.height()).sum();
    let valign_offset = match shape.vertical_align() {
        VerticalAlign::Center => (selrect.height() - total_height) / 2.0,
        VerticalAlign::Bottom => selrect.height() - total_height,
        _ => 0.0,
    };

    let mut y_offset = valign_offset;

    for (para_idx, laid_out_para) in layout_paragraphs.iter().enumerate() {
        let para_height = laid_out_para.height();

        if para_idx < start.paragraph || para_idx > end.paragraph {
            y_offset += para_height;
            continue;
        }

        if para_idx >= paragraphs.len() {
            y_offset += para_height;
            continue;
        }

        let para = &paragraphs[para_idx];
        let para_char_count: usize = para
            .children()
            .iter()
            .map(|span| span.text.chars().count())
            .sum();
        let range_start = if para_idx == start.paragraph {
            start.char_offset
        } else {
            0
        };

        let range_end = if para_idx == end.paragraph {
            end.char_offset
        } else {
            para_char_count
        };

        if range_start < range_end {
            use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
            let text_boxes = laid_out_para.get_rects_for_range(
                range_start..range_end,
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            );

            for text_box in text_boxes {
                let r = text_box.rect;
                rects.push(Rect::from_xywh(
                    selrect.x() + r.left(),
                    selrect.y() + y_offset + r.top(),
                    r.width(),
                    r.height(),
                ));
            }
        }

        y_offset += para_height;
    }

    rects
}

/// Get total character count in a paragraph.
fn paragraph_char_count(para: &Paragraph) -> usize {
    para.children()
        .iter()
        .map(|span| span.text.chars().count())
        .sum()
}

/// Clamp a cursor position to valid bounds within the text content.
fn clamp_cursor(cursor: TextCursor, paragraphs: &[Paragraph]) -> TextCursor {
    if paragraphs.is_empty() {
        return TextCursor::new(0, 0);
    }

    let para_idx = cursor.paragraph.min(paragraphs.len() - 1);
    let para_len = paragraph_char_count(&paragraphs[para_idx]);
    let char_offset = cursor.char_offset.min(para_len);

    TextCursor::new(para_idx, char_offset)
}

/// Move cursor left by one character.
fn move_cursor_backward(cursor: &TextCursor, paragraphs: &[Paragraph]) -> TextCursor {
    if cursor.char_offset > 0 {
        TextCursor::new(cursor.paragraph, cursor.char_offset - 1)
    } else if cursor.paragraph > 0 {
        let prev_para = cursor.paragraph - 1;
        let char_count = paragraph_char_count(&paragraphs[prev_para]);
        TextCursor::new(prev_para, char_count)
    } else {
        *cursor
    }
}

/// Move cursor right by one character.
fn move_cursor_forward(cursor: &TextCursor, paragraphs: &[Paragraph]) -> TextCursor {
    let para = &paragraphs[cursor.paragraph];
    let char_count = paragraph_char_count(para);

    if cursor.char_offset < char_count {
        TextCursor::new(cursor.paragraph, cursor.char_offset + 1)
    } else if cursor.paragraph < paragraphs.len() - 1 {
        TextCursor::new(cursor.paragraph + 1, 0)
    } else {
        *cursor
    }
}

/// Move cursor up by one line.
fn move_cursor_up(
    cursor: &TextCursor,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
    _shape: &Shape,
) -> TextCursor {
    // TODO: Implement proper line-based navigation using line metrics
    if cursor.paragraph > 0 {
        let prev_para = cursor.paragraph - 1;
        let char_count = paragraph_char_count(&paragraphs[prev_para]);
        let new_offset = cursor.char_offset.min(char_count);
        TextCursor::new(prev_para, new_offset)
    } else {
        TextCursor::new(cursor.paragraph, 0)
    }
}

/// Move cursor down by one line.
fn move_cursor_down(
    cursor: &TextCursor,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
    _shape: &Shape,
) -> TextCursor {
    // TODO: Implement proper line-based navigation using line metrics
    if cursor.paragraph < paragraphs.len() - 1 {
        let next_para = cursor.paragraph + 1;
        let char_count = paragraph_char_count(&paragraphs[next_para]);
        let new_offset = cursor.char_offset.min(char_count);
        TextCursor::new(next_para, new_offset)
    } else {
        let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
        TextCursor::new(cursor.paragraph, char_count)
    }
}

/// Move cursor to start of current line.
fn move_cursor_line_start(cursor: &TextCursor, _paragraphs: &[Paragraph]) -> TextCursor {
    // TODO: Implement proper line-start using line metrics
    TextCursor::new(cursor.paragraph, 0)
}

/// Move cursor to end of current line.
fn move_cursor_line_end(cursor: &TextCursor, paragraphs: &[Paragraph]) -> TextCursor {
    // TODO: Implement proper line-end using line metrics
    let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
    TextCursor::new(cursor.paragraph, char_count)
}

// ============================================================================
// HELPERS: Text Modification
// ============================================================================

fn find_span_at_offset(para: &Paragraph, char_offset: usize) -> Option<(usize, usize)> {
    let children = para.children();
    let mut accumulated = 0;
    for (span_idx, span) in children.iter().enumerate() {
        let span_len = span.text.chars().count();
        if char_offset <= accumulated + span_len {
            return Some((span_idx, char_offset - accumulated));
        }
        accumulated += span_len;
    }
    if !children.is_empty() {
        let last_idx = children.len() - 1;
        let last_len = children[last_idx].text.chars().count();
        return Some((last_idx, last_len));
    }
    None
}

/// Insert text at a cursor position. Returns the new character offset after insertion.
fn insert_text_at_cursor(
    text_content: &mut TextContent,
    cursor: &TextCursor,
    text: &str,
) -> Option<usize> {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let para = &mut paragraphs[cursor.paragraph];

    let children = para.children_mut();
    if children.is_empty() {
        return None;
    }

    if children.len() == 1 && children[0].text.is_empty() {
        children[0].set_text(text.to_string());
        return Some(text.chars().count());
    }

    let (span_idx, offset_in_span) = find_span_at_offset(para, cursor.char_offset)?;

    let children = para.children_mut();
    let span = &mut children[span_idx];
    let mut new_text = span.text.clone();

    let byte_offset = new_text
        .char_indices()
        .nth(offset_in_span)
        .map(|(i, _)| i)
        .unwrap_or(new_text.len());

    new_text.insert_str(byte_offset, text);
    span.set_text(new_text);

    Some(cursor.char_offset + text.chars().count())
}

/// Delete a range of text specified by a selection.
fn delete_selection_range(text_content: &mut TextContent, selection: &TextSelection) {
    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs_mut();
    if start.paragraph >= paragraphs.len() {
        return;
    }

    if start.paragraph == end.paragraph {
        delete_range_in_paragraph(
            &mut paragraphs[start.paragraph],
            start.char_offset,
            end.char_offset,
        );
    } else {
        let start_para_len = paragraph_char_count(&paragraphs[start.paragraph]);
        delete_range_in_paragraph(
            &mut paragraphs[start.paragraph],
            start.char_offset,
            start_para_len,
        );

        delete_range_in_paragraph(&mut paragraphs[end.paragraph], 0, end.char_offset);

        if end.paragraph < paragraphs.len() {
            let end_para_children: Vec<_> =
                paragraphs[end.paragraph].children_mut().drain(..).collect();
            paragraphs[start.paragraph]
                .children_mut()
                .extend(end_para_children);
        }

        if end.paragraph < paragraphs.len() {
            paragraphs.drain((start.paragraph + 1)..=end.paragraph);
        }

        let children = paragraphs[start.paragraph].children_mut();
        let has_content = children.iter().any(|span| !span.text.is_empty());
        if has_content {
            children.retain(|span| !span.text.is_empty());
        } else if children.len() > 1 {
            children.truncate(1);
        }
    }
}

/// Delete a range of characters within a single paragraph.
fn delete_range_in_paragraph(para: &mut Paragraph, start_offset: usize, end_offset: usize) {
    if start_offset >= end_offset {
        return;
    }

    let mut accumulated = 0;
    let mut delete_start_span = None;
    let mut delete_end_span = None;

    for (idx, span) in para.children().iter().enumerate() {
        let span_len = span.text.chars().count();
        let span_end = accumulated + span_len;

        if delete_start_span.is_none() && start_offset < span_end {
            delete_start_span = Some((idx, start_offset - accumulated));
        }
        if end_offset <= span_end {
            delete_end_span = Some((idx, end_offset - accumulated));
            break;
        }
        accumulated += span_len;
    }

    let Some((start_span_idx, start_in_span)) = delete_start_span else {
        return;
    };
    let Some((end_span_idx, end_in_span)) = delete_end_span else {
        return;
    };

    let children = para.children_mut();

    if start_span_idx == end_span_idx {
        let span = &mut children[start_span_idx];
        let text = span.text.clone();
        let chars: Vec<char> = text.chars().collect();

        let start_clamped = start_in_span.min(chars.len());
        let end_clamped = end_in_span.min(chars.len());

        let new_text: String = chars[..start_clamped]
            .iter()
            .chain(chars[end_clamped..].iter())
            .collect();
        span.set_text(new_text);
    } else {
        let start_span = &mut children[start_span_idx];
        let text = start_span.text.clone();
        let start_char_count = text.chars().count();
        let start_clamped = start_in_span.min(start_char_count);
        let new_text: String = text.chars().take(start_clamped).collect();
        start_span.set_text(new_text);

        let end_span = &mut children[end_span_idx];
        let text = end_span.text.clone();
        let end_char_count = text.chars().count();
        let end_clamped = end_in_span.min(end_char_count);
        let new_text: String = text.chars().skip(end_clamped).collect();
        end_span.set_text(new_text);

        if end_span_idx > start_span_idx + 1 {
            children.drain((start_span_idx + 1)..end_span_idx);
        }
    }

    let has_content = children.iter().any(|span| !span.text.is_empty());
    if has_content {
        children.retain(|span| !span.text.is_empty());
    } else if !children.is_empty() {
        children.truncate(1);
    }
}

/// Delete the character before the cursor. Returns the new cursor position.
fn delete_char_before(text_content: &mut TextContent, cursor: &TextCursor) -> Option<TextCursor> {
    if cursor.char_offset > 0 {
        let paragraphs = text_content.paragraphs_mut();
        let para = &mut paragraphs[cursor.paragraph];
        let delete_pos = cursor.char_offset - 1;
        delete_range_in_paragraph(para, delete_pos, cursor.char_offset);
        Some(TextCursor::new(cursor.paragraph, delete_pos))
    } else if cursor.paragraph > 0 {
        let prev_para_idx = cursor.paragraph - 1;
        let paragraphs = text_content.paragraphs_mut();
        let prev_para_len = paragraph_char_count(&paragraphs[prev_para_idx]);

        let current_children: Vec<_> = paragraphs[cursor.paragraph]
            .children_mut()
            .drain(..)
            .collect();
        paragraphs[prev_para_idx]
            .children_mut()
            .extend(current_children);

        paragraphs.remove(cursor.paragraph);

        Some(TextCursor::new(prev_para_idx, prev_para_len))
    } else {
        None
    }
}

/// Delete the character after the cursor.
fn delete_char_after(text_content: &mut TextContent, cursor: &TextCursor) {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return;
    }

    let para_len = paragraph_char_count(&paragraphs[cursor.paragraph]);

    if cursor.char_offset < para_len {
        let para = &mut paragraphs[cursor.paragraph];
        delete_range_in_paragraph(para, cursor.char_offset, cursor.char_offset + 1);
    } else if cursor.paragraph < paragraphs.len() - 1 {
        let next_para_idx = cursor.paragraph + 1;
        let next_children: Vec<_> = paragraphs[next_para_idx].children_mut().drain(..).collect();
        paragraphs[cursor.paragraph]
            .children_mut()
            .extend(next_children);

        paragraphs.remove(next_para_idx);
    }
}

/// Split a paragraph at the cursor position. Returns true if split was successful.
fn split_paragraph_at_cursor(text_content: &mut TextContent, cursor: &TextCursor) -> bool {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return false;
    }

    let para = &paragraphs[cursor.paragraph];

    let Some((span_idx, offset_in_span)) = find_span_at_offset(para, cursor.char_offset) else {
        return false;
    };

    let mut new_para_children = Vec::new();
    let children = para.children();

    let current_span = &children[span_idx];
    let span_text = current_span.text.clone();
    let chars: Vec<char> = span_text.chars().collect();

    if offset_in_span < chars.len() {
        let after_text: String = chars[offset_in_span..].iter().collect();
        let mut new_span = current_span.clone();
        new_span.set_text(after_text);
        new_para_children.push(new_span);
    }

    for child in children.iter().skip(span_idx + 1) {
        new_para_children.push(child.clone());
    }

    if new_para_children.is_empty() {
        let mut empty_span = current_span.clone();
        empty_span.set_text(String::new());
        new_para_children.push(empty_span);
    }

    let text_align = para.text_align();
    let text_direction = para.text_direction();
    let text_decoration = para.text_decoration();
    let text_transform = para.text_transform();
    let line_height = para.line_height();
    let letter_spacing = para.letter_spacing();

    let para = &mut paragraphs[cursor.paragraph];
    let children = para.children_mut();

    children.truncate(span_idx + 1);

    if !children.is_empty() {
        let span = &mut children[span_idx];
        let text = span.text.clone();
        let new_text: String = text.chars().take(offset_in_span).collect();
        span.set_text(new_text);
    }

    let new_para = crate::shapes::Paragraph::new(
        text_align,
        text_direction,
        text_decoration,
        text_transform,
        line_height,
        letter_spacing,
        new_para_children,
    );

    paragraphs.insert(cursor.paragraph + 1, new_para);

    true
}
