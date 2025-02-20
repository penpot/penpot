use skia_safe::{self as skia};

use super::RenderState;
use crate::shapes::Shadow;

pub fn render_drop_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);
    render_state.surfaces.shape.draw(
        &mut render_state.surfaces.shadow.canvas(),
        (0.0, 0.0),
        render_state.sampling_options,
        Some(&shadow_paint),
    );

    render_state.surfaces.shadow.draw(
        &mut render_state.surfaces.current.canvas(),
        (0.0, 0.0),
        render_state.sampling_options,
        Some(&skia::Paint::default()),
    );

    render_state
        .surfaces
        .shadow
        .canvas()
        .clear(skia::Color::TRANSPARENT);
}

pub fn render_inner_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);

    render_state.surfaces.shape.draw(
        render_state.surfaces.shadow.canvas(),
        (0.0, 0.0),
        render_state.sampling_options,
        Some(&shadow_paint),
    );

    render_state.surfaces.shadow.draw(
        &mut render_state.surfaces.overlay.canvas(),
        (0.0, 0.0),
        render_state.sampling_options,
        None,
    );

    render_state
        .surfaces
        .shadow
        .canvas()
        .clear(skia::Color::TRANSPARENT);
}
