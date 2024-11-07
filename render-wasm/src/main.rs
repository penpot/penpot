pub mod render;
pub mod shapes;
pub mod state;
pub mod utils;

use skia_safe as skia;

use crate::state::State;
use crate::shapes::{Shape, ShapeKind, Rect, Matrix};
use crate::utils::uuid_from_u32_quartet;

static mut STATE: Option<Box<State>> = None;

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) {
    let mut gpu_state = render::create_gpu_state();
    let surface = render::create_surface(&mut gpu_state, width, height);
    // let state = State::with_capacity(gpu_state, surface, 2048);

    let state_box = Box::new(State::with_capacity(gpu_state, surface, 2048));
    unsafe {
        STATE = Some(state_box);
    }
}

/// This is called from JS when the window is resized.
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn resize_surface(state: *mut State, width: i32, height: i32) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    let surface = render::create_surface(&mut state.gpu_state, width, height);
    state.set_surface(surface);
}

/// Draws a rect at the specified coordinates with the give ncolor
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn draw_rect(state: *mut State, x1: f32, y1: f32, x2: f32, y2: f32) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    let r = skia::Rect::new(x1, y1, x2, y2);
    render::render_rect(&mut state.surface, r, skia::Color::RED);
}

#[no_mangle]
pub unsafe extern "C" fn draw_all_shapes(state: *mut State, zoom: f32, pan_x: f32, pan_y: f32) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");

    reset_canvas(state);
    scale(state, zoom, zoom);
    translate(state, pan_x, pan_y);

    render::render_all(state);

    flush(state);
}

#[no_mangle]
pub unsafe extern "C" fn flush(state: *mut State) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    state
        .gpu_state
        .context
        .flush_and_submit_surface(&mut state.surface, None);
}

#[no_mangle]
pub unsafe extern "C" fn translate(state: *mut State, dx: f32, dy: f32) {
    (*state).surface.canvas().translate((dx, dy));
}

#[no_mangle]
pub unsafe extern "C" fn scale(state: *mut State, sx: f32, sy: f32) {
    (*state).surface.canvas().scale((sx, sy));
}

#[no_mangle]
pub unsafe extern "C" fn reset_canvas(state: *mut State) {
    let state = unsafe { state.as_mut() }.expect("got an invalid state pointer");
    state.surface.canvas().clear(skia_safe::Color::TRANSPARENT);
    state.surface.canvas().reset_matrix();
    flush(state);
}

#[no_mangle]
pub unsafe extern "C" fn shape_create(state: *mut State, a: u32, b: u32, c: u32, d: u32) {
    let uuid = uuid_from_u32_quartet(a, b, c, d);
    (*state).shapes.insert(uuid, Shape {
        id: uuid,
        kind: ShapeKind::Rect,
        selrect: Rect { x1: 0., y1: 0., x2: 100., y2: 100. },
        matrix: Matrix { a: 1., b: 0., c: 0., d: 1., e: 0., f: 0. }
    });
    (*state).display_list.push(uuid);
}

#[no_mangle]
pub unsafe extern "C" fn shape_set_selrect(state: *mut State, a: u32, b: u32, c: u32, d: u32, x1: f32, y1: f32, x2: f32, y2: f32) {
    let uuid = uuid_from_u32_quartet(a, b, c, d);
    if let Some(shape: Shape) (*state).shapes.get(uuid);
    shape.selrect.x1 = x1;
    shape.selrect.y1 = y1;
    shape.selrect.x2 = x2;
    shape.selrect.y2 = y2;
}

#[no_mangle]
pub unsafe extern "C" fn shape_list(state: *mut State) {
    for id in (*state).display_list.iter_mut() {
        let shape: Option<&Shape> = (*state).shapes.get(id);
        println!("{}", shape.unwrap().id);
    }
}

fn main() {
    render::init_gl();
}
