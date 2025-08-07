use skia_safe::{self as skia};
use std::collections::HashMap;

use crate::math::{Matrix, Rect};
use crate::shapes::modifiers::grid_layout::grid_cell_data;
use crate::shapes::{Shape, StructureEntry};
use crate::state::ShapesPool;
use crate::uuid::Uuid;

pub fn render_overlay(
    zoom: f32,
    canvas: &skia::Canvas,
    shape: &Shape,
    shapes: &ShapesPool,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    let cells = grid_cell_data(shape, shapes, modifiers, structure, true);

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 111, 224));

    paint.set_stroke_width(1.0 / zoom);

    for cell in cells.iter() {
        let rect = Rect::from_xywh(cell.anchor.x, cell.anchor.y, cell.width, cell.height);
        canvas.draw_rect(rect, &paint);
    }
}
