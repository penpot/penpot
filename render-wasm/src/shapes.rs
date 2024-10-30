use crate::render::{render_rect, State};
use skia_safe as skia;

#[derive(Debug, Clone, Copy)]
pub struct Selrect {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct Transform {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct Shape {
    pub selrect: Selrect,
    pub transform: Transform,
}

pub static mut SHAPES_BUFFER: [Shape; 2048] = [Shape {
    selrect: Selrect {
        x1: 0.0,
        y1: 0.0,
        x2: 0.0,
        y2: 0.0,
    },
    transform: Transform {
        a: 0.0,
        b: 0.0,
        c: 0.0,
        d: 0.0,
        e: 0.0,
        f: 0.0,
    },
}; 2048];

pub(crate) fn draw_all(state: &mut State) {
    let shapes;
    unsafe {
        shapes = SHAPES_BUFFER.iter();
    }

    for shape in shapes {
        let r = skia::Rect::new(
            shape.selrect.x1,
            shape.selrect.y1,
            shape.selrect.x2,
            shape.selrect.y2,
        );
        println!("{:?}", shape.transform);
        render_rect(&mut state.surface, r, skia::Color::RED);
    }
}
