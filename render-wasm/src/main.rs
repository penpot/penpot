pub mod render;

use skia_safe as skia;

use render::State;

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) -> Box<render::State> {
    let mut gpu_state = render::create_gpu_state();
    let surface = render::create_surface(&mut gpu_state, width, height);

    let state = State::new(gpu_state, surface);

    Box::new(state)
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

fn main() {
    render::init_gl();
}
