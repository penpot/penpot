use skia_safe::{self as skia};

use super::RenderState;
use crate::shapes::Shadow;

pub fn render_drop_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);
    render_state.drawing_surface.draw(
        &mut render_state.shadow_surface.canvas(),
        (0.0, 0.0),
        skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
        Some(&shadow_paint),
    );

    render_state.shadow_surface.draw(
        &mut render_state.render_surface.canvas(),
        (0.0, 0.0),
        skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
        Some(&skia::Paint::default()),
    );

    render_state
        .shadow_surface
        .canvas()
        .clear(skia::Color::TRANSPARENT);
}

pub fn render_inner_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = shadow.to_paint(scale);

    render_state.drawing_surface.draw(
        render_state.shadow_surface.canvas(),
        (0.0, 0.0),
        skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
        Some(&shadow_paint),
    );

    render_state.shadow_surface.draw(
        &mut render_state.overlay_surface.canvas(),
        (0.0, 0.0),
        skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
        None,
    );

    render_state
        .shadow_surface
        .canvas()
        .clear(skia::Color::TRANSPARENT);
}
