use crate::render::{render_rect, State};
use skia_safe as skia;

#[derive(Debug, Clone, Copy)]
pub struct Selrect {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

pub type Shape = Selrect; // temp

pub static mut SHAPES_BUFFER: [Shape; 2048] = [Selrect {
    x1: 0.0,
    y1: 0.0,
    x2: 0.0,
    y2: 0.0,
}; 2048];

pub(crate) fn draw_all(state: &mut State) {
    let shapes;
    unsafe {
        shapes = SHAPES_BUFFER.iter();
    }

    for shape in shapes {
        let r = skia::Rect::new(shape.x1, shape.y1, shape.x2, shape.y2);
        render_rect(&mut state.surface, r, skia::Color::RED);
    }
}
