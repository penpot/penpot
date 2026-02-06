use skia_safe::{self as skia, Color4f};

use super::{RenderState, ShapesPoolRef, SurfaceId};
use crate::render::grid_layout;
use crate::shapes::{Layout, Type};

pub fn render(render_state: &mut RenderState, shapes: ShapesPoolRef) {
    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    canvas.clear(Color4f::new(0.0, 0.0, 0.0, 0.0));
    canvas.save();

    let viewbox = render_state.viewbox;
    let zoom = viewbox.zoom * render_state.options.dpr();

    canvas.scale((zoom, zoom));

    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    let show_grid_id = render_state.show_grid;

    if let Some(id) = show_grid_id {
        if let Some(shape) = shapes.get(&id) {
            grid_layout::render_overlay(zoom, canvas, shape, shapes);
        }
    }

    // Render overlays for empty grid frames
    for shape in shapes.iter() {
        if shape.id.is_nil() || !shape.children.is_empty() {
            continue;
        }

        if show_grid_id == Some(shape.id) {
            continue;
        }

        let Type::Frame(frame) = &shape.shape_type else {
            continue;
        };

        if !matches!(frame.layout, Some(Layout::GridLayout(_, _))) {
            continue;
        }

        if let Some(shape) = shapes.get(&shape.id) {
            grid_layout::render_overlay(zoom, canvas, shape, shapes);
        }
    }

    canvas.restore();
    render_state.surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
}
