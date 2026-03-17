use macros::{wasm_error, ToJs};

use crate::math::{Matrix, Point, Rect};
use crate::mem;
use crate::shapes::{
    FontStyle, Shape, TextContent, TextDecoration, TextPositionWithAffinity, TextTransform, Type,
    VerticalAlign,
};
use crate::state::{TextEditorEvent, TextSelection};
use crate::utils::uuid_from_u32_quartet;
use crate::utils::uuid_to_u32_quartet;
use crate::wasm::fills::write_fills_to_bytes;
use crate::wasm::text::helpers as text_helpers;
use crate::{with_state, with_state_mut, STATE};
use skia_safe::Color;

#[derive(PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum CursorDirection {
    Backward = 0,
    Forward = 1,
    LineBefore = 2,
    LineAfter = 3,
    LineStart = 4,
    LineEnd = 5,
}

// ============================================================================
// STATE MANAGEMENT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_apply_theme(
    selection_color: u32,
    cursor_width: f32,
    cursor_color: u32,
) {
    with_state_mut!(state, {
        // NOTE: In the future could be interesting to fill al this data from
        // a structure pointer.
        state.text_editor_state.theme.selection_color = Color::new(selection_color);
        state.text_editor_state.theme.cursor_width = cursor_width;
        state.text_editor_state.theme.cursor_color = Color::new(cursor_color);
    })
}

#[no_mangle]
pub extern "C" fn text_editor_focus(a: u32, b: u32, c: u32, d: u32) -> bool {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);

        let Some(shape) = state.shapes.get(&shape_id) else {
            return false;
        };

        if !matches!(shape.shape_type, Type::Text(_)) {
            return false;
        }

        state.text_editor_state.focus(shape_id);
        true
    })
}

#[no_mangle]
pub extern "C" fn text_editor_blur() -> bool {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return false;
        }
        state.text_editor_state.blur();
        true
    })
}

#[no_mangle]
pub extern "C" fn text_editor_dispose() -> bool {
    with_state_mut!(state, {
        state.text_editor_state.dispose();
        true
    })
}

#[no_mangle]
pub extern "C" fn text_editor_has_selection() -> bool {
    with_state!(state, { state.text_editor_state.selection.is_selection() })
}

#[no_mangle]
pub extern "C" fn text_editor_has_focus() -> bool {
    with_state!(state, { state.text_editor_state.has_focus })
}

#[no_mangle]
pub extern "C" fn text_editor_has_focus_with_id(a: u32, b: u32, c: u32, d: u32) -> bool {
    with_state!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        let Some(active_shape_id) = state.text_editor_state.active_shape_id else {
            return false;
        };
        state.text_editor_state.has_focus && active_shape_id == shape_id
    })
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
pub extern "C" fn text_editor_select_all() -> bool {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return false;
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return false;
        };

        let Some(shape) = state.shapes.get(&shape_id) else {
            return false;
        };

        let Type::Text(text_content) = &shape.shape_type else {
            return false;
        };
        state.text_editor_state.select_all(text_content)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_select_word_boundary(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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

        let point = Point::new(x, y);
        if let Some(position) = text_content.get_caret_position_from_shape_coords(&point) {
            state
                .text_editor_state
                .select_word_boundary(text_content, &position);
        }
    })
}

#[no_mangle]
pub extern "C" fn text_editor_poll_event() -> u8 {
    with_state_mut!(state, { state.text_editor_state.poll_event() as u8 })
}

// ============================================================================
// SELECTION MANAGEMENT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_pointer_down(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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
        let point = Point::new(x, y);
        state.text_editor_state.start_pointer_selection();
        if let Some(position) = text_content.get_caret_position_from_shape_coords(&point) {
            state.text_editor_state.set_caret_from_position(&position);
        }
    });
}

#[no_mangle]
pub extern "C" fn text_editor_pointer_move(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return;
        }
        let point = Point::new(x, y);
        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };
        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };
        if !state.text_editor_state.is_pointer_selection_active {
            return;
        }
        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };

        if let Some(position) = text_content.get_caret_position_from_shape_coords(&point) {
            state
                .text_editor_state
                .extend_selection_from_position(&position);
        }
    });
}

#[no_mangle]
pub extern "C" fn text_editor_pointer_up(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return;
        }
        let point = Point::new(x, y);
        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };
        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };
        if !state.text_editor_state.is_pointer_selection_active {
            return;
        }
        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };
        if let Some(position) = text_content.get_caret_position_from_shape_coords(&point) {
            state
                .text_editor_state
                .extend_selection_from_position(&position);
        }
        state.text_editor_state.stop_pointer_selection();
    });
}

#[no_mangle]
pub extern "C" fn text_editor_set_cursor_from_offset(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return;
        }

        let point = Point::new(x, y);
        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };
        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };
        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };
        if let Some(position) = text_content.get_caret_position_from_shape_coords(&point) {
            state.text_editor_state.set_caret_from_position(&position);
        }
    });
}

#[no_mangle]
pub extern "C" fn text_editor_set_cursor_from_point(x: f32, y: f32) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return;
        }

        let view_matrix: Matrix = state.render_state.viewbox.get_matrix();
        let point = Point::new(x, y);
        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return;
        };
        let Some(shape) = state.shapes.get(&shape_id) else {
            return;
        };
        let shape_matrix = shape.get_matrix();
        let Type::Text(text_content) = &shape.shape_type else {
            return;
        };
        if let Some(position) =
            text_content.get_caret_position_from_screen_coords(&point, &view_matrix, &shape_matrix)
        {
            state.text_editor_state.set_caret_from_position(&position);
        }
    });
}

// ============================================================================
// TEXT OPERATIONS
// ============================================================================

// FIXME: Review if all the return Ok(()) should be Err instead.
#[no_mangle]
#[wasm_error]
pub extern "C" fn text_editor_insert_text() -> Result<()> {
    let bytes = crate::mem::bytes();
    let text = match String::from_utf8(bytes) {
        Ok(text) => text,
        Err(_) => return Ok(()),
    };

    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return Ok(());
        }

        let Some(shape_id) = state.text_editor_state.active_shape_id else {
            return Ok(());
        };

        let Some(shape) = state.shapes.get_mut(&shape_id) else {
            return Ok(());
        };

        let Type::Text(text_content) = &mut shape.shape_type else {
            return Ok(());
        };

        let selection = state.text_editor_state.selection;

        if selection.is_selection() {
            text_helpers::delete_selection_range(text_content, &selection);
            let start = selection.start();
            state.text_editor_state.selection.set_caret(start);
        }

        let cursor = state.text_editor_state.selection.focus;

        if let Some(new_cursor) =
            text_helpers::insert_text_with_newlines(text_content, &cursor, &text)
        {
            state.text_editor_state.selection.set_caret(new_cursor);
        }

        text_content.layout.paragraphs.clear();
        text_content.layout.paragraph_builders.clear();

        state.text_editor_state.reset_blink();
        state
            .text_editor_state
            .push_event(TextEditorEvent::ContentChanged);
        state
            .text_editor_state
            .push_event(TextEditorEvent::NeedsLayout);

        state.render_state.mark_touched(shape_id);
    });

    crate::mem::free_bytes()?;
    Ok(())
}

#[no_mangle]
pub extern "C" fn text_editor_delete_backward(word_boundary: bool) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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

        state
            .text_editor_state
            .delete_backward(text_content, word_boundary);
        state.render_state.mark_touched(shape_id);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_delete_forward(word_boundary: bool) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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

        state
            .text_editor_state
            .delete_forward(text_content, word_boundary);
        state.render_state.mark_touched(shape_id);
    });
}

#[no_mangle]
pub extern "C" fn text_editor_insert_paragraph() {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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

        state.text_editor_state.insert_paragraph(text_content);
        state.render_state.mark_touched(shape_id);
    });
}

// ============================================================================
// NAVIGATION
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_move_cursor(
    direction: CursorDirection,
    word_boundary: bool,
    extend_selection: bool,
) {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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

        state.text_editor_state.move_cursor(
            text_content,
            direction,
            word_boundary,
            extend_selection,
        );
    });
}

// ============================================================================
// RENDERING & EXPORT
// ============================================================================

#[no_mangle]
pub extern "C" fn text_editor_get_cursor_rect() -> *mut u8 {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus || !state.text_editor_state.cursor_visible {
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

/// Serialize a skia TextAlign to its raw u32 representation.
fn text_align_to_raw(align: &skia_safe::textlayout::TextAlign) -> u32 {
    use skia_safe::textlayout::TextAlign;
    match *align {
        TextAlign::Left => 0,
        TextAlign::Center => 1,
        TextAlign::Right => 2,
        TextAlign::Justify => 3,
        _ => 0,
    }
}

/// Serialize a skia TextDirection to its raw u32 representation.
fn text_direction_to_raw(dir: &skia_safe::textlayout::TextDirection) -> u32 {
    use skia_safe::textlayout::TextDirection;
    match *dir {
        TextDirection::LTR => 0,
        TextDirection::RTL => 1,
    }
}

/// Serialize an Option<TextDecoration> to its raw u32 representation.
fn text_decoration_to_raw(dec: &Option<TextDecoration>) -> u32 {
    match dec.as_ref() {
        None => 0,
        Some(d) if *d == TextDecoration::UNDERLINE => 1,
        Some(d) if *d == TextDecoration::LINE_THROUGH => 2,
        Some(d) if *d == TextDecoration::OVERLINE => 3,
        _ => 0,
    }
}

/// Serialize an Option<TextTransform> to its raw u32 representation.
fn text_transform_to_raw(transform: &Option<TextTransform>) -> u32 {
    match transform.as_ref() {
        None => 0,
        Some(TextTransform::Uppercase) => 1,
        Some(TextTransform::Lowercase) => 2,
        Some(TextTransform::Capitalize) => 3,
    }
}

/// Serialize a FontStyle to its raw u32 representation.
fn font_style_to_raw(style: &FontStyle) -> u32 {
    match *style {
        FontStyle::Normal => 0,
        FontStyle::Italic => 1,
    }
}

/// Binary layout for TextEditorStyles (all offsets in bytes, 4-byte aligned):
///
///  Offset  Size  Field
///  ------  ----  -----
///   0       4    vertical_align (u32)
///   4       4    text_align.state (u32)
///   8       4    text_align.value (u32)
///  12       4    text_direction.state (u32)
///  16       4    text_direction.value (u32)
///  20       4    text_decoration.state (u32)
///  24       4    text_decoration.value (u32)
///  28       4    text_transform.state (u32)
///  32       4    text_transform.value (u32)
///  36       4    font_size.state (u32)
///  40       4    font_size.value (f32)
///  44       4    font_weight.state (u32)
///  48       4    font_weight.value (i32)
///  52       4    line_height.state (u32)
///  56       4    line_height.value (f32)
///  60       4    letter_spacing.state (u32)
///  64       4    letter_spacing.value (f32)
///  68       4    font_family.state (u32)
///  72      16    font_family.id (Uuid: 4×u32 LE)
///  88       4    font_family.style (u32)
///  92       4    font_family.weight (u32)
///  96       4    font_variant_id.state (u32)
/// 100      16    font_variant_id.value (Uuid: 4×u32 LE)
/// 116       4    fills_count (u32)
/// 120+    var    fills (RAW_FILL_DATA_SIZE each, same format as set_shape_fills)
const STYLES_HEADER_SIZE: usize = 120;

#[no_mangle]
pub extern "C" fn text_editor_get_current_styles() -> *mut u8 {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
            return std::ptr::null_mut();
        }

        if state.text_editor_state.selection.is_collapsed() {
            return std::ptr::null_mut();
        }

        let Some(_shape_id) = state.text_editor_state.active_shape_id else {
            return std::ptr::null_mut();
        };

        let styles = &state.text_editor_state.current_styles;

        // Serialize fills using the existing RawFillData format
        let fill_bytes = write_fills_to_bytes(styles.fills.clone());
        let total_size = STYLES_HEADER_SIZE + fill_bytes.len();
        let mut bytes = vec![0u8; total_size];

        // vertical_align
        bytes[0..4].copy_from_slice(&u32::to_le_bytes(styles.vertical_align as u32));

        // text_align: state + value
        bytes[4..8].copy_from_slice(&u32::to_le_bytes(*styles.text_align.state() as u32));
        if let Some(val) = styles.text_align.value() {
            bytes[8..12].copy_from_slice(&u32::to_le_bytes(text_align_to_raw(val)));
        }

        // text_direction: state + value
        bytes[12..16].copy_from_slice(&u32::to_le_bytes(*styles.text_direction.state() as u32));
        if let Some(val) = styles.text_direction.value() {
            bytes[16..20].copy_from_slice(&u32::to_le_bytes(text_direction_to_raw(val)));
        }

        // text_decoration: state + value
        bytes[20..24].copy_from_slice(&u32::to_le_bytes(*styles.text_decoration.state() as u32));
        // text_decoration merges Option<TextDecoration>, so value() is Option<Option<TextDecoration>>
        // When Single: value() is Some(inner) where inner is the Option<TextDecoration>
        if styles.text_decoration.is_single() {
            let inner = styles.text_decoration.value();
            // inner is &Option<TextDecoration>: the Some/None tells us Single vs not,
            // but for text_decoration the merged value IS an Option<TextDecoration>.
            // So we serialize the inner Option directly.
            bytes[24..28].copy_from_slice(&u32::to_le_bytes(text_decoration_to_raw(inner)));
        }

        // text_transform: state + value (same pattern as text_decoration)
        bytes[28..32].copy_from_slice(&u32::to_le_bytes(*styles.text_transform.state() as u32));
        if styles.text_transform.is_single() {
            let inner = styles.text_transform.value();
            bytes[32..36].copy_from_slice(&u32::to_le_bytes(text_transform_to_raw(inner)));
        }

        // font_size: state + value (f32)
        bytes[36..40].copy_from_slice(&u32::to_le_bytes(*styles.font_size.state() as u32));
        if let Some(val) = styles.font_size.value() {
            bytes[40..44].copy_from_slice(&f32::to_le_bytes(*val));
        }

        // font_weight: state + value (i32)
        bytes[44..48].copy_from_slice(&u32::to_le_bytes(*styles.font_weight.state() as u32));
        if let Some(val) = styles.font_weight.value() {
            bytes[48..52].copy_from_slice(&i32::to_le_bytes(*val));
        }

        // line_height: state + value (f32)
        bytes[52..56].copy_from_slice(&u32::to_le_bytes(*styles.line_height.state() as u32));
        if let Some(val) = styles.line_height.value() {
            bytes[56..60].copy_from_slice(&f32::to_le_bytes(*val));
        }

        // letter_spacing: state + value (f32)
        bytes[60..64].copy_from_slice(&u32::to_le_bytes(*styles.letter_spacing.state() as u32));
        if let Some(val) = styles.letter_spacing.value() {
            bytes[64..68].copy_from_slice(&f32::to_le_bytes(*val));
        }

        // font_family: state + id (16 bytes) + style (u32) + weight (u32)
        bytes[68..72].copy_from_slice(&u32::to_le_bytes(*styles.font_family.state() as u32));
        if let Some(family) = styles.font_family.value() {
            let id_bytes: [u8; 16] = family.id().into();
            bytes[72..88].copy_from_slice(&id_bytes);
            bytes[88..92].copy_from_slice(&u32::to_le_bytes(font_style_to_raw(&family.style())));
            bytes[92..96].copy_from_slice(&u32::to_le_bytes(family.weight()));
        }

        // font_variant_id: state + uuid (16 bytes)
        bytes[96..100].copy_from_slice(&u32::to_le_bytes(*styles.font_variant_id.state() as u32));
        if let Some(variant_id) = styles.font_variant_id.value() {
            let id_bytes: [u8; 16] = (*variant_id).into();
            bytes[100..116].copy_from_slice(&id_bytes);
        }

        // fills_count + fill data
        bytes[116..120].copy_from_slice(&u32::to_le_bytes(styles.fills.len() as u32));
        if !fill_bytes.is_empty() {
            bytes[STYLES_HEADER_SIZE..].copy_from_slice(&fill_bytes);
        }

        mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_get_selection_rects() -> *mut u8 {
    with_state_mut!(state, {
        if !state.text_editor_state.has_focus {
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
        if !state.text_editor_state.has_focus {
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
        if !state.text_editor_state.has_focus {
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
            if para_idx > start.paragraph {
                result.push('\n');
            }
            result.push_str(&para_text);
        }
        let mut bytes = result.into_bytes();
        bytes.push(0);
        crate::mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn text_editor_get_selection(buffer_ptr: *mut u32) -> bool {
    with_state!(state, {
        if !state.text_editor_state.selection.is_selection() {
            return false;
        }
        let sel = &state.text_editor_state.selection;
        unsafe {
            *buffer_ptr = sel.anchor.paragraph as u32;
            *buffer_ptr.add(1) = sel.anchor.offset as u32;
            *buffer_ptr.add(2) = sel.focus.paragraph as u32;
            *buffer_ptr.add(3) = sel.focus.offset as u32;
        }
        true
    })
}

// ============================================================================
// HELPERS: Cursor & Selection
// ============================================================================

fn get_cursor_rect(
    text_content: &TextContent,
    cursor: &TextPositionWithAffinity,
    shape: &Shape,
) -> Option<Rect> {
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
            let char_pos = cursor.offset;

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
