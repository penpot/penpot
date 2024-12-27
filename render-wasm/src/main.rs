mod debug;
mod math;
mod mem;
mod render;
mod shapes;
mod state;
mod utils;
mod view;

use crate::shapes::Kind;
use crate::shapes::Path;
use skia_safe as skia;

use crate::state::State;
use crate::utils::uuid_from_u32_quartet;

static mut STATE: Option<Box<State>> = None;

extern "C" {
    fn emscripten_GetProcAddress(
        name: *const ::std::os::raw::c_char,
    ) -> *const ::std::os::raw::c_void;
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
pub extern "C" fn set_render_options(debug: u32, dpr: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let render_state = state.render_state();

    render_state.set_debug_flags(debug);
    render_state.set_dpr(dpr);
}

#[no_mangle]
pub extern "C" fn set_canvas_background(raw_color: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    let color = skia::Color::new(raw_color);
    state.set_background_color(color);
}

#[no_mangle]
pub unsafe extern "C" fn render() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_all(true);
}

#[no_mangle]
pub unsafe extern "C" fn render_without_cache() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_all(false);
}

#[no_mangle]
pub unsafe extern "C" fn navigate() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.navigate();
}

#[no_mangle]
pub extern "C" fn reset_canvas() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state().reset_canvas();
}

#[no_mangle]
pub extern "C" fn resize_viewbox(width: i32, height: i32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.resize(width, height);
}

#[no_mangle]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state().viewbox.set_all(zoom, x, y);
}

#[no_mangle]
pub extern "C" fn set_view_zoom(zoom: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state().viewbox.set_zoom(zoom);
}

#[no_mangle]
pub extern "C" fn set_view_xy(x: f32, y: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state().viewbox.set_pan_xy(x, y);
}

#[no_mangle]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    state.use_shape(id);
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_kind_circle() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        shape.set_kind(Kind::Circle(math::Rect::new_empty()));
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_kind_rect() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        shape.set_kind(Kind::Rect(math::Rect::new_empty()));
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_kind_path() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let p = Path::try_from(Vec::new()).unwrap();
        shape.set_kind(Kind::Path(p));
    }
}

#[no_mangle]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_selrect(left, top, right, bottom);
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_clip_content(clip_content: bool) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_clip(clip_content);
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_rotation(rotation: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_rotation(rotation);
    }
}

#[no_mangle]
pub extern "C" fn set_shape_transform(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_transform(a, b, c, d, e, f);
    }
}

#[no_mangle]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape) = state.current_shape() {
        shape.add_child(id);
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_children() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.clear_children();
    }
}

#[no_mangle]
pub extern "C" fn add_shape_solid_fill(raw_color: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let color = skia::Color::new(raw_color);
        shape.add_fill(shapes::Fill::Solid(color));
    }
}

#[no_mangle]
pub extern "C" fn add_shape_linear_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.add_fill(shapes::Fill::new_linear_gradient(
            (start_x, start_y),
            (end_x, end_y),
            opacity,
        ))
    }
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
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.add_fill(shapes::Fill::new_radial_gradient(
            (start_x, start_y),
            (end_x, end_y),
            opacity,
            width,
        ))
    }
}

#[no_mangle]
pub extern "C" fn add_shape_fill_stops(ptr: *mut shapes::RawStopData, n_stops: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        let len = n_stops as usize;

        unsafe {
            let buffer = Vec::<shapes::RawStopData>::from_raw_parts(ptr, len, len);
            shape
                .add_fill_gradient_stops(buffer)
                .expect("could not add gradient stops");
            mem::free_bytes();
        }
    }
}

#[no_mangle]
pub extern "C" fn store_image(a: u32, b: u32, c: u32, d: u32, size: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);

    unsafe {
        let image_bytes =
            Vec::<u8>::from_raw_parts(mem::buffer_ptr(), size as usize, size as usize);
        match state.render_state().add_image(id, &image_bytes) {
            Err(msg) => {
                eprintln!("{}", msg);
            }
            _ => {}
        }
        mem::free_bytes();
    }
}

#[no_mangle]
pub extern "C" fn is_image_cached(a: u32, b: u32, c: u32, d: u32) -> bool {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    state.render_state().has_image(&id)
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
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape) = state.current_shape() {
        shape.add_fill(shapes::Fill::new_image_fill(
            id,
            (alpha * 0xff as f32).floor() as u8,
            (width, height),
        ));
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.clear_fills();
    }
}

#[no_mangle]
pub extern "C" fn set_shape_blend_mode(mode: i32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_blend_mode(render::BlendMode::from(mode));
    }
}

#[no_mangle]
pub extern "C" fn set_shape_opacity(opacity: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_opacity(opacity);
    }
}

#[no_mangle]
pub extern "C" fn set_shape_hidden(hidden: bool) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.set_hidden(hidden);
    }
}

#[no_mangle]
pub extern "C" fn set_shape_path_content() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        let bytes = mem::bytes();
        let raw_segments = bytes
            .chunks(size_of::<shapes::RawPathData>())
            .map(|data| shapes::RawPathData {
                data: data.try_into().unwrap(),
            })
            .collect();
        shape.set_path_segments(raw_segments).unwrap();
    }
}

#[no_mangle]
pub extern "C" fn add_shape_center_stroke(width: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.add_stroke(shapes::Stroke::new_center_stroke(width))
    }
}

#[no_mangle]
pub extern "C" fn add_shape_inner_stroke(width: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.add_stroke(shapes::Stroke::new_inner_stroke(width))
    }
}

#[no_mangle]
pub extern "C" fn add_shape_outer_stroke(width: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.add_stroke(shapes::Stroke::new_outer_stroke(width))
    }
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_solid_fill(raw_color: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let color = skia::Color::new(raw_color);
        shape
            .set_stroke_fill(shapes::Fill::Solid(color))
            .expect("could not add stroke solid fill");
    }
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_linear_fill(
    start_x: f32,
    start_y: f32,
    end_x: f32,
    end_y: f32,
    opacity: f32,
) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape
            .set_stroke_fill(shapes::Fill::new_linear_gradient(
                (start_x, start_y),
                (end_x, end_y),
                opacity,
            ))
            .expect("could not add stroke linear fill");
    }
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
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape
            .set_stroke_fill(shapes::Fill::new_radial_gradient(
                (start_x, start_y),
                (end_x, end_y),
                opacity,
                width,
            ))
            .expect("could not add stroke radial fill");
    }
}

#[no_mangle]
pub extern "C" fn add_shape_stroke_stops(ptr: *mut shapes::RawStopData, n_stops: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        let len = n_stops as usize;

        unsafe {
            let buffer = Vec::<shapes::RawStopData>::from_raw_parts(ptr, len, len);
            shape
                .add_stroke_gradient_stops(buffer)
                .expect("could not add gradient stops");
            mem::free_bytes();
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
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape) = state.current_shape() {
        shape
            .set_stroke_fill(shapes::Fill::new_image_fill(
                id,
                (alpha * 0xff as f32).floor() as u8,
                (width, height),
            ))
            .expect("could not add stroke image fill");
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_strokes() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.clear_strokes();
    }
}

fn main() {
    init_gl();
}
