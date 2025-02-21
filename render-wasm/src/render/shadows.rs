use skia_safe::{self as skia};

use super::{RenderState, SurfaceId};
use crate::shapes::Shadow;

pub fn render_drop_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);
    render_state
        .surfaces
        .draw_into(SurfaceId::Fills, SurfaceId::Shadow, Some(&shadow_paint));
    render_state
        .surfaces
        .draw_into(SurfaceId::Strokes, SurfaceId::Shadow, Some(&shadow_paint));

    render_state.surfaces.draw_into(
        SurfaceId::Shadow,
        SurfaceId::Current,
        Some(&skia::Paint::default()),
    );

    render_state
        .surfaces
        .canvas(SurfaceId::Shadow)
        .clear(skia::Color::TRANSPARENT);
}

pub fn render_inner_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);

    render_state
        .surfaces
        .draw_into(SurfaceId::Fills, SurfaceId::Shadow, Some(&shadow_paint));

    render_state
        .surfaces
        .draw_into(SurfaceId::Shadow, SurfaceId::Overlay, None); // , None

    render_state
        .surfaces
        .canvas(SurfaceId::Shadow)
        .clear(skia::Color::TRANSPARENT);
}
