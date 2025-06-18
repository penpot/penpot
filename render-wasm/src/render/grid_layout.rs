use skia_safe::{self as skia};
use std::collections::HashMap;

use crate::math::{Bounds, Matrix, Rect};
use crate::shapes::modifiers::grid_layout::{calculate_tracks, create_cell_data};
use crate::shapes::{modified_children_ids, Frame, Layout, Shape, StructureEntry, Type};
use crate::uuid::Uuid;

pub fn render_overlay(
    zoom: f32,
    canvas: &skia::Canvas,
    shape: &Shape,
    shapes: &HashMap<Uuid, &mut Shape>,
    modifiers: &HashMap<Uuid, Matrix>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    let Type::Frame(Frame {
        layout: Some(Layout::GridLayout(layout_data, grid_data)),
        ..
    }) = &shape.shape_type
    else {
        return;
    };

    let bounds = &HashMap::<Uuid, Bounds>::new();

    let shape = &mut shape.clone();
    if let Some(modifiers) = modifiers.get(&shape.id) {
        shape.apply_transform(modifiers);
    }

    let layout_bounds = shape.bounds();
    let children = modified_children_ids(shape, structure.get(&shape.id), false);

    let column_tracks = calculate_tracks(
        true,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let row_tracks = calculate_tracks(
        false,
        shape,
        layout_data,
        grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let cells = create_cell_data(
        &layout_bounds,
        &children,
        shapes,
        &grid_data.cells,
        &column_tracks,
        &row_tracks,
        true,
    );

    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 111, 224));

    paint.set_stroke_width(1.0 / zoom);

    for cell in cells.iter() {
        let rect = Rect::from_xywh(cell.anchor.x, cell.anchor.y, cell.width, cell.height);
        canvas.draw_rect(rect, &paint);
    }
}
