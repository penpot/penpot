use skia_safe as skia;

mod debug;
mod math;
mod mem;
mod render;
mod shapes;
mod state;
mod utils;
mod view;
mod wasm;

use crate::mem::SerializableResult;
use crate::shapes::{BoolType, ConstraintH, ConstraintV, TransformEntry, Type};

use crate::utils::uuid_from_u32_quartet;
use state::State;

pub(crate) static mut STATE: Option<Box<State>> = None;

extern "C" {
    fn emscripten_GetProcAddress(
        name: *const ::std::os::raw::c_char,
    ) -> *const ::std::os::raw::c_void;
}

#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        $block
    };
}

#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

fn init_gl() {
    unsafe {
        gl::load_with(|addr| {
            let addr = std::ffi::CString::new(addr).unwrap();
            emscripten_GetProcAddress(addr.into_raw() as *const _) as *const _
        });
    }
}

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) {
    let state_box = Box::new(State::new(width, height, 2048));
    unsafe {
        STATE = Some(state_box);
    }
}

#[no_mangle]
pub extern "C" fn clean_up() {
    unsafe { STATE = None }
    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn clear_cache() {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.clear_cache();
    });
}

#[no_mangle]
pub extern "C" fn set_render_options(debug: u32, dpr: f32) {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.set_debug_flags(debug);
        render_state.set_dpr(dpr);
    });
}

#[no_mangle]
pub extern "C" fn set_canvas_background(raw_color: u32) {
    with_state!(state, {
        let color = skia::Color::new(raw_color);
        state.set_background_color(color);
    });
}

#[no_mangle]
pub extern "C" fn render(timestamp: i32) {
    with_state!(state, {
        state.start_render_loop(timestamp).expect("Error rendering");
    });
}

#[no_mangle]
pub extern "C" fn render_from_cache() {
    with_state!(state, {
        state.render_from_cache();
    });
}

#[no_mangle]
pub extern "C" fn process_animation_frame(timestamp: i32) {
    with_state!(state, {
        state
            .process_animation_frame(timestamp)
            .expect("Error processing animation frame");
    });
}

#[no_mangle]
pub extern "C" fn reset_canvas() {
    with_state!(state, {
        state.render_state().reset_canvas();
    });
}

#[no_mangle]
pub extern "C" fn resize_viewbox(width: i32, height: i32) {
    with_state!(state, {
        state.resize(width, height);
    });
}

#[no_mangle]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.invalidate_cache_if_needed();
        render_state.viewbox.set_all(zoom, x, y);
    });
}

#[no_mangle]
pub extern "C" fn set_view_zoom(zoom: f32) {
    with_state!(state, {
        state.render_state().viewbox.set_zoom(zoom);
    });
}

#[no_mangle]
pub extern "C" fn set_view_xy(x: f32, y: f32) {
    with_state!(state, {
        state.render_state().viewbox.set_pan_xy(x, y);
    });
}

#[no_mangle]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
}

#[no_mangle]
pub unsafe extern "C" fn set_parent(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("Got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape) = state.current_shape() {
        shape.set_parent(id);
    }
}

#[no_mangle]
pub extern "C" fn set_shape_masked_group(masked: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_masked(masked);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_bool_type(raw_bool_type: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_bool_type(BoolType::from(raw_bool_type));
    });
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_type(shape_type: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_shape_type(Type::from(shape_type));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_selrect(left, top, right, bottom);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_clip_content(clip_content: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_clip(clip_content);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_rotation(rotation: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_rotation(rotation);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_transform(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_transform(a, b, c, d, e, f);
    });
}

#[no_mangle]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_child(id);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_children() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_children();
    });
}

#[no_mangle]
pub extern "C" fn add_shape_solid_fill(raw_color: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        shape.add_fill(shapes::Fill::Solid(color));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_linear_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_fill(shapes::Fill::new_linear_gradient(
            (start_x, start_y),
            (end_x, end_y),
            opacity,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_radial_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_fill(shapes::Fill::new_radial_gradient(
            (start_x, start_y),
            (end_x, end_y),
            opacity,
            width,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_fill_stops() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<shapes::RawStopData>())
        .map(|data| shapes::RawStopData::from_bytes(data.try_into().unwrap()))
        .collect();

    with_current_shape!(state, |shape: &mut Shape| {
        shape
            .add_fill_gradient_stops(entries)
            .expect("could not add gradient stops");
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn store_image(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let image_bytes = mem::bytes();

        match state.render_state().add_image(id, &image_bytes) {
            Err(msg) => {
                eprintln!("{}", msg);
            }
            _ => {}
        }

        mem::free_bytes();
    });
}

#[no_mangle]
pub extern "C" fn is_image_cached(a: u32, b: u32, c: u32, d: u32) -> bool {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        return state.render_state().has_image(&id);
    });
}

#[no_mangle]
pub extern "C" fn add_shape_image_fill(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    alpha: f32,
    width: i32,
    height: i32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_fill(shapes::Fill::new_image_fill(
            id,
            (alpha * 0xff as f32).floor() as u8,
            (width, height),
        ));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_fills();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_svg_raw_content() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let svg_raw_content = String::from_utf8(bytes)
            .unwrap()
            .trim_end_matches('\0')
            .to_string();
        shape
            .set_svg_raw_content(svg_raw_content)
            .expect("Failed to set svg raw content");
    });
}

#[no_mangle]
pub extern "C" fn set_shape_blend_mode(mode: i32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_blend_mode(render::BlendMode::from(mode));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_opacity(opacity: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_opacity(opacity);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_h(constraint: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_constraint_h(ConstraintH::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_v(constraint: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_constraint_v(ConstraintV::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_hidden(hidden: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_hidden(hidden);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_blur(blur_type: u8, hidden: bool, value: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_blur(blur_type, hidden, value);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_path_content() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let raw_segments = bytes
            .chunks(size_of::<shapes::RawPathData>())
            .map(|data| shapes::RawPathData {
                data: data.try_into().unwrap(),
            })
            .collect();
        shape.set_path_segments(raw_segments).unwrap();
    });
}

#[no_mangle]
pub extern "C" fn add_shape_center_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_center_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_inner_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_inner_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_outer_stroke(width: f32, style: u8, cap_start: u8, cap_end: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.add_stroke(shapes::Stroke::new_outer_stroke(
            width, style, cap_start, cap_end,
        ));
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_solid_fill(raw_color: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        shape
            .set_stroke_fill(shapes::Fill::Solid(color))
            .expect("could not add stroke solid fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_linear_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape
            .set_stroke_fill(shapes::Fill::new_linear_gradient(
                (start_x, start_y),
                (end_x, end_y),
                opacity,
            ))
            .expect("could not add stroke linear fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_radial_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
    width: f32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape
            .set_stroke_fill(shapes::Fill::new_radial_gradient(
                (start_x, start_y),
                (end_x, end_y),
                opacity,
                width,
            ))
            .expect("could not add stroke radial fill");
    });
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_stops() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<shapes::RawStopData>())
        .map(|data| shapes::RawStopData::from_bytes(data.try_into().unwrap()))
        .collect();

    with_current_shape!(state, |shape: &mut Shape| {
        shape
            .add_stroke_gradient_stops(entries)
            .expect("could not add gradient stops");
    });

    mem::free_bytes();
}

// Extracts a string from the bytes slice until the next null byte (0) and returns the result as a `String`.
// Updates the `start` index to the end of the extracted string.
fn extract_string(start: &mut usize, bytes: &[u8]) -> String {
    match bytes[*start..].iter().position(|&b| b == 0) {
        Some(pos) => {
            let end = *start + pos;
            let slice = &bytes[*start..end];
            *start = end + 1; // Move the `start` pointer past the null byte
                              // Call to unsafe function within an unsafe block
            unsafe { String::from_utf8_unchecked(slice.to_vec()) }
        }
        None => {
            *start = bytes.len(); // Move `start` to the end if no null byte is found
            String::new()
        }
    }
}

#[no_mangle]
pub extern "C" fn add_shape_image_stroke(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    alpha: f32,
    width: i32,
    height: i32,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape
            .set_stroke_fill(shapes::Fill::new_image_fill(
                id,
                (alpha * 0xff as f32).floor() as u8,
                (width, height),
            ))
            .expect("could not add stroke image fill");
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_strokes() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_strokes();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_corners(r1: f32, r2: f32, r3: f32, r4: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_corners((r1, r2, r3, r4));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_path_attrs(num_attrs: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let mut start = 0;
        for _ in 0..num_attrs {
            let name = extract_string(&mut start, &bytes);
            let value = extract_string(&mut start, &bytes);
            shape.set_path_attr(name, value);
        }
    });
}

#[no_mangle]
pub extern "C" fn propagate_modifiers() -> *mut u8 {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state!(state, {
        let result = shapes::propagate_modifiers(state, entries);
        return mem::write_vec(result);
    });
}

#[no_mangle]
pub extern "C" fn clean_modifiers() {
    with_state!(state, {
        state.modifiers.clear();
    });
}

#[no_mangle]
pub extern "C" fn set_modifiers() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state!(state, {
        for entry in entries {
            state.modifiers.insert(entry.id, entry.transform);
        }
        state.render_state().clear_cache();
    });
}

#[no_mangle]
pub extern "C" fn add_shape_shadow(
    raw_color: u32,
    blur: f32,
    spread: f32,
    x: f32,
    y: f32,
    raw_style: u8,
    hidden: bool,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        let style = shapes::ShadowStyle::from(raw_style);
        let shadow = shapes::Shadow::new(color, blur, spread, (x, y), style, hidden);
        shape.add_shadow(shadow);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_shadows() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_shadows();
    });
}

#[no_mangle]
pub extern "C" fn set_flex_layout_data(
    dir: u8,
    row_gap: f32,
    column_gap: f32,
    align_items: u8,
    align_content: u8,
    justify_items: u8,
    justify_content: u8,
    wrap_type: u8,
    padding_top: f32,
    padding_right: f32,
    padding_bottom: f32,
    padding_left: f32,
) {
    let dir = shapes::Direction::from_u8(dir);
    let align_items = shapes::AlignItems::from_u8(align_items);
    let align_content = shapes::AlignContent::from_u8(align_content);
    let justify_items = shapes::JustifyItems::from_u8(justify_items);
    let justify_content = shapes::JustifyContent::from_u8(justify_content);
    let wrap_type = shapes::WrapType::from_u8(wrap_type);

    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_flex_layout_data(
            dir,
            row_gap,
            column_gap,
            align_items,
            align_content,
            justify_items,
            justify_content,
            wrap_type,
            padding_top,
            padding_right,
            padding_bottom,
            padding_left,
        );
    });
}

#[no_mangle]
pub extern "C" fn set_layout_child_data(
    margin_top: f32,
    margin_right: f32,
    margin_bottom: f32,
    margin_left: f32,
    h_sizing: u8,
    v_sizing: u8,
    has_max_h: bool,
    max_h: f32,
    has_min_h: bool,
    min_h: f32,
    has_max_w: bool,
    max_w: f32,
    has_min_w: bool,
    min_w: f32,
    has_align_self: bool,
    align_self: u8,
    is_absolute: bool,
    z_index: i32,
) {
    let h_sizing = shapes::Sizing::from_u8(h_sizing);
    let v_sizing = shapes::Sizing::from_u8(v_sizing);
    let max_h = if has_max_h { Some(max_h) } else { None };
    let min_h = if has_min_h { Some(min_h) } else { None };
    let max_w = if has_max_w { Some(max_w) } else { None };
    let min_w = if has_min_w { Some(min_w) } else { None };
    let align_self = if has_align_self {
        shapes::AlignSelf::from_u8(align_self)
    } else {
        None
    };

    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_flex_layout_child_data(
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing,
            v_sizing,
            max_h,
            min_h,
            max_w,
            min_w,
            align_self,
            is_absolute,
            z_index,
        );
    });
}

#[no_mangle]
pub extern "C" fn set_grid_layout_data() {}

#[no_mangle]
pub extern "C" fn add_grid_track() {}

#[no_mangle]
pub extern "C" fn set_grid_cell() {}

fn main() {
    init_gl();
}
