use std::collections::HashMap;
use skia_safe::{self as skia};

use super::RenderState;
use super::surfaces::SurfaceId;
use crate::shapes::{modified_children_ids, Shape, Type, Frame, Layout, StructureEntry};
use crate::math::{Rect, Bounds};
use crate::uuid::Uuid;
use crate::shapes::modifiers::grid_layout::{create_cell_data, calculate_tracks};


pub fn render_overlay_all(
    render_state: &mut RenderState,
    shapes: &HashMap<Uuid, &mut Shape>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    for shape in shapes.values() {
        render_overlay(
            render_state,
            shape,
            shapes,
            structure
        );
    }
}

pub fn render_overlay(
    render_state: &mut RenderState,
    shape: &Shape,
    shapes: &HashMap<Uuid, &mut Shape>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) {
    let Type::Frame(Frame {
        layout: Some(Layout::GridLayout(layout_data, grid_data)), .. }) = &shape.shape_type else {
        return;
    };
        
    let bounds = &HashMap::<Uuid, Bounds>::new();
    let layout_bounds = shape.bounds();
    let children = modified_children_ids(shape, structure.get(&shape.id));

    let column_tracks = calculate_tracks(
        true,
        shape,
        &layout_data,
        &grid_data,
        &layout_bounds,
        &grid_data.cells,
        shapes,
        bounds,
    );

    let row_tracks = calculate_tracks(
        false,
        shape,
        &layout_data,
        &grid_data,
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
        true
    );


    // println!("{:?}", column_tracks);
    // println!("{:?}", row_tracks);

    let viewbox = render_state.viewbox;
    let navigate_zoom = viewbox.zoom * render_state.options.dpr();
    
    let mut paint = skia::Paint::default();
    paint.set_style(skia::PaintStyle::Stroke);
    paint.set_color(skia::Color::from_rgb(255, 111, 224));

    paint.set_stroke_width(1.0 / navigate_zoom);

    for cell in cells.iter() {
        let rect = Rect::from_xywh(cell.anchor.x, cell.anchor.y, cell.width, cell.height);
        render_state
            .surfaces
            .canvas(SurfaceId::UI)
            .draw_rect(rect, &paint);
    }
}
