pub mod render;
pub mod shapes;
pub mod state;
pub mod utils;

use std::ptr::addr_of;

use uuid::Uuid;
use skia_safe as skia;

use crate::state::State;
use crate::shapes::{Shape, Rect, Matrix};
use crate::utils::uuid_from_u32_quartet;

static mut UUID: Uuid = Uuid::nil();
static mut SHAPE: Option<Box<Shape>> = None;
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

/// Draws a rect at the specified coordinates with the give ncolor
/// # Safety
#[no_mangle]
pub unsafe extern "C" fn draw_rect(x1: f32, y1: f32, x2: f32, y2: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    let r = skia::Rect::new(x1, y1, x2, y2);
    render::render_rect(&mut state.render_state.surface, r, skia::Color::RED);
}

#[no_mangle]
pub unsafe extern "C" fn draw_all_shapes(zoom: f32, pan_x: f32, pan_y: f32) {
    println!("draw_all_shapes");
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");

    reset_canvas();
    scale(zoom, zoom);
    translate( pan_x, pan_y);

    render::render_all(state);

    flush();
}

#[no_mangle]
pub unsafe extern "C" fn flush() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state
        .render_state
        .gpu_state
        .context
        .flush_and_submit_surface(&mut state.render_state.surface, None);
}

#[no_mangle]
pub unsafe extern "C" fn translate(dx: f32, dy: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state.surface.canvas().translate((dx, dy));
}

#[no_mangle]
pub unsafe extern "C" fn scale(sx: f32, sy: f32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state.surface.canvas().scale((sx, sy));
}

#[no_mangle]
pub unsafe extern "C" fn reset_canvas() {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    state.render_state.surface.canvas().clear(skia_safe::Color::TRANSPARENT);
    state.render_state.surface.canvas().reset_matrix();
    // flush(state);
}

#[no_mangle]
pub unsafe extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    let state = unsafe { STATE.as_mut() }.expect("got an invalid state pointer");
    UUID = uuid_from_u32_quartet(a, b, c, d);
    println!("UUID {}", UUID);
    let shape = match state.shapes.get(&*addr_of!(UUID)) {
        Some(s) => {
            println!("Not created");
            *s
        },
        None => {
            println!("Created");
            let s = Shape {
                id: UUID,
                kind: shapes::Kind::Rect,
                selrect: Rect { x1: 0., y1: 0., x2: 0., y2: 0. },
                transform: Matrix { a: 1., b: 0., c: 0., d: 1., e: 0., f: 0. },
                rotation: 0.,
            };
            state.shapes.insert(UUID, s);
            state.display_list.push(UUID);
            s
        }
    };
    // NOTE: Check if we could
    SHAPE = Some(Box::new(shape));
    println!("SHAPE IMPRIME CERO {:?}", SHAPE);
    println!("shape {:?}", shape);
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_selrect(x1: f32, y1: f32, x2: f32, y2: f32) {
    if let Some(shape) = SHAPE.as_mut() {
        println!("Set Shape SelRect {} {} {} {}", x1, y1, x2, y2);
        shape.selrect.x1 = x1;
        shape.selrect.y1 = y1;
        shape.selrect.x2 = x2;
        shape.selrect.y2 = y2;
        println!("Set Shape SelRect {:?}", shape.selrect);
        // println!("SHAPE {:?}", SHAPE);
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_rotation(rotation: f32) {
    if let Some(shape) = SHAPE.as_mut() {
        shape.rotation = rotation;
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_x(x: f32) {
    if let Some(shape) = SHAPE.as_mut() {
        let width = shape.selrect.x2 - shape.selrect.x1;
        shape.selrect.x1 = x;
        shape.selrect.x2 = x + width;
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_y(y: f32) {
    if let Some(shape) = SHAPE.as_mut() {
        let height = shape.selrect.y2 - shape.selrect.y1;
        shape.selrect.y1 = y;
        shape.selrect.y2 = y + height;
    }
}

#[no_mangle]
pub unsafe extern "C" fn set_shape_transform(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
    if let Some(shape) = SHAPE.as_mut() {
        shape.transform.a = a;
        shape.transform.b = b;
        shape.transform.c = c;
        shape.transform.d = d;
        shape.transform.e = e;
        shape.transform.f = f;
    }
}

#[no_mangle]
pub unsafe extern "C" fn list_shapes(state: *mut State) {
    for id in (*state).display_list.iter_mut() {
        let shape: Option<&Shape> = (*state).shapes.get(id);
        println!("{}", shape.unwrap().id);
    }
}

fn main() {
    render::init_gl();
}
