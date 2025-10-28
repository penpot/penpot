use skia_safe::{self as skia, Color4f};

use super::{RenderState, ShapesPoolRef, SurfaceId};
use crate::render::grid_layout;

pub fn render(render_state: &mut RenderState, shapes: ShapesPoolRef) {
    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    canvas.clear(Color4f::new(0.0, 0.0, 0.0, 0.0));
    canvas.save();

    let viewbox = render_state.viewbox;
    let zoom = viewbox.zoom * render_state.options.dpr();

    canvas.scale((zoom, zoom));

    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    let canvas = render_state.surfaces.canvas(SurfaceId::UI);

    if let Some(id) = render_state.show_grid {
        if let Some(shape) = shapes.get(&id) {
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
