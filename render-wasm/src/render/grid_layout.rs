use skia_safe::{self as skia};

use crate::shapes::modifiers::grid_layout::grid_cell_data;
use crate::shapes::Shape;
use crate::state::ShapesPoolRef;

pub fn render_overlay(zoom: f32, canvas: &skia::Canvas, shape: &Shape, shapes: ShapesPoolRef) {
    let cells: Vec<crate::shapes::grid_layout::CellData<'_>> = grid_cell_data(shape, shapes, true);
    let bounds = shape.bounds();

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 111, 224));
    paint.set_anti_alias(shape.should_use_antialias(zoom));

    paint.set_stroke_width(1.0 / zoom);

    for cell in cells.iter() {
        let hv = bounds.hv(cell.width);
        let vv = bounds.vv(cell.height);
        let points = [
            cell.anchor,
            cell.anchor + hv,
            cell.anchor + hv + vv,
            cell.anchor + vv,
        ];
        let polygon = skia::Path::polygon(&points, true, None, None);
        canvas.draw_path(&polygon, &paint);
    }
}
