use skia_safe::{self as skia, Color4f};

use super::{RenderState, ShapesPoolRef, SurfaceId};
use crate::render::{grid_layout, rulers};

pub fn render(render_state: &mut RenderState, shapes: ShapesPoolRef) {
    let canvas = render_state.surfaces.canvas(SurfaceId::UI);
    let viewbox = render_state.viewbox;
    let zoom = viewbox.zoom * render_state.options.dpr;
    let show_grid_id = render_state.show_grid;

    canvas.clear(Color4f::new(0.0, 0.0, 0.0, 0.0));
    canvas.save();
    canvas.scale((zoom, zoom));
    canvas.translate((-viewbox.area.left, -viewbox.area.top));

    if let Some(id) = show_grid_id {
        if let Some(shape) = shapes.get(&id) {
            grid_layout::render_overlay(
                zoom,
                render_state.options.antialias_threshold,
                canvas,
                shape,
                shapes,
            );
        }
    }

    // Render overlays for empty grid frames
    let empty_grid_ids: std::collections::HashSet<crate::uuid::Uuid> =
        std::mem::take(&mut render_state.empty_grid_frame_ids);
    for id in &empty_grid_ids {
        if show_grid_id == Some(*id) {
            continue;
        }
        if let Some(shape) = shapes.get(id) {
            grid_layout::render_overlay(
                zoom,
                render_state.options.antialias_threshold,
                canvas,
                shape,
                shapes,
            );
        }
    }
    render_state.empty_grid_frame_ids = empty_grid_ids;

    let viewbox = render_state.viewbox;
    let ruler_state = render_state.rulers;
    rulers::render(canvas, viewbox, &render_state.fonts, &ruler_state);

    canvas.restore();

    render_state.surfaces.draw_into(
        SurfaceId::UI,
        SurfaceId::Target,
        Some(&skia::Paint::default()),
    );
}
