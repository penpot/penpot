use skia_safe::{self as skia};

use crate::math::Rect;
use crate::shapes::modifiers::grid_layout::grid_cell_data;
use crate::shapes::Shape;
use crate::state::ShapesPoolRef;

pub fn render_overlay(zoom: f32, canvas: &skia::Canvas, shape: &Shape, shapes: ShapesPoolRef) {
    let cells = grid_cell_data(shape, shapes, true);

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 111, 224));

    paint.set_stroke_width(1.0 / zoom);

    for cell in cells.iter() {
        let rect = Rect::from_xywh(cell.anchor.x, cell.anchor.y, cell.width, cell.height);
        canvas.draw_rect(rect, &paint);
    }
}
