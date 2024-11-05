use skia_safe as skia;

use crate::render::{render_rect, State};

#[derive(Debug, Clone, Copy, Default)]
pub struct Rect {
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
    pub selrect: Rect,
    pub transform: Transform,
}

impl Shape {
    #[inline]
    fn translation(&self) -> (f32, f32) {
        (self.transform.e, self.transform.f)
    }

    #[inline]
    fn scale(&self) -> (f32, f32) {
        (self.transform.a, self.transform.d)
    }

    #[inline]
    fn skew(&self) -> (f32, f32) {
        (self.transform.c, self.transform.b)
    }
}

pub static mut SHAPES_BUFFER: [Shape; 1024] = [Shape {
    selrect: Rect {
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
}; 1024];

pub(crate) fn draw_all(state: &mut State) {
    let shapes;
    unsafe {
        shapes = SHAPES_BUFFER.iter();
    }

    for shape in shapes {
        draw_shape(state, shape);
    }
}

#[inline]
fn draw_shape(state: &mut State, shape: &Shape) {
    let r = skia::Rect::new(
        shape.selrect.x1,
        shape.selrect.y1,
        shape.selrect.x2,
        shape.selrect.y2,
    );

    state.surface.canvas().save();

    let mut matrix = skia::Matrix::new_identity();
    matrix.set_scale_translate(shape.scale(), shape.translation());
    let (skew_x, skew_y) = shape.skew();
    matrix.set_skew_x(skew_x);
    matrix.set_skew_y(skew_y);
    state.surface.canvas().concat(&matrix);

    render_rect(&mut state.surface, r, skia::Color::RED);

    state.surface.canvas().restore();
}
