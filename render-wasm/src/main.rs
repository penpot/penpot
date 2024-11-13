pub mod render;
pub mod shapes;
pub mod state;
pub mod utils;

use skia_safe as skia;
use uuid::Uuid;

use crate::state::State;
use crate::utils::uuid_from_u32_quartet;

static mut STATE: Option<Box<State>> = None;

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) {
    let state_box = Box::new(State::with_capacity(width, height, 2048));
    unsafe {
        STATE = Some(state_box);
    }
}

/// This is called from JS when the window is resized.
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn resize_surface(width: i32, height: i32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let surface = render::create_surface(&mut state.render_state.gpu_state, width, height);
    state.set_surface(surface);
}

#[no_mangle]
pub unsafe extern "C" fn draw_all_shapes(zoom: f32, pan_x: f32, pan_y: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    reset_canvas();
    render::scale(state, zoom, zoom);
    render::translate(state, pan_x, pan_y);

    render::render_shape_tree(state, Uuid::nil());

    render::flush(state);
}

#[no_mangle]
pub extern "C" fn reset_canvas() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state
        .render_state
        .surface
        .canvas()
        .clear(skia_safe::Color::TRANSPARENT);
    state.render_state.surface.canvas().reset_matrix();
}

#[no_mangle]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    state.use_shape(id);
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_selrect(x1: f32, y1: f32, x2: f32, y2: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    if let Some(shape) = state.current_shape() {
        shape.selrect.x1 = x1;
        shape.selrect.y1 = y1;
        shape.selrect.x2 = x2;
        shape.selrect.y2 = y2;
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_rotation(rotation: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.rotation = rotation;
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_transform(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.transform.a = a;
        shape.transform.b = b;
        shape.transform.c = c;
        shape.transform.d = d;
        shape.transform.e = e;
        shape.transform.f = f;
    }
}

#[no_mangle]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let id = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape) = state.current_shape() {
        shape.children.push(id);
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_children() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.children.clear();
    }
}

#[no_mangle]
pub extern "C" fn add_shape_solid_fill(r: u8, g: u8, b: u8, a: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        let alpha: u8 = (a * 0xff as f32).floor() as u8;
        let color = skia::Color::from_argb(alpha, r, g, b);
        shape.add_fill(shapes::Fill::from(color));
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_fills() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    if let Some(shape) = state.current_shape() {
        shape.clear_fills();
    }
}

fn main() {
    render::init_gl();
}
