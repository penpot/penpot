use skia_safe::{self as skia};

// Import the fills module
use super::{fills, RenderState, SurfaceId};
use crate::shapes::{Fill, Shadow, Shape};

pub fn fill_drop_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    fill: &Fill,
) {
    let shadow_paint = shadow.to_paint(render_state.viewbox.zoom * render_state.options.dpr());
    fills::render(render_state, &shape, fill, Some(&shadow_paint));
}

pub fn render_drop_shadow(render_state: &mut RenderState) {
    render_state
        .surfaces
        .draw_into(SurfaceId::Shadow, SurfaceId::Current, None);

    render_state
        .surfaces
        .canvas(SurfaceId::Shadow)
        .clear(skia::Color::TRANSPARENT);
}

pub fn render_inner_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    scale: f32,
    render_over_fills: bool,
) {
    let shadow_paint = shadow.to_paint(scale);

    if render_over_fills {
        render_state
            .surfaces
            .draw_into(SurfaceId::Fills, SurfaceId::Shadow, Some(&shadow_paint));
    } else {
        render_state
            .surfaces
            .draw_into(SurfaceId::Strokes, SurfaceId::Shadow, Some(&shadow_paint));
    }

    render_state
        .surfaces
        .draw_into(SurfaceId::Shadow, SurfaceId::Overlay, None);

    render_state
        .surfaces
        .canvas(SurfaceId::Shadow)
        .clear(skia::Color::TRANSPARENT);
}
