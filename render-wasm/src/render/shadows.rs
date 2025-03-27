use super::{RenderState, SurfaceId};
use crate::shapes::{Shadow, Shape, Type};
use skia_safe::{self as skia, Paint};

// Drop Shadows
pub fn render_drop_shadows(render_state: &mut RenderState, shape: &Shape, antialias: bool) {
    if shape.has_fills() {
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_drop_shadow(render_state, &shape, &shadow, antialias);
        }
    } else {
        let scale = render_state.get_scale();
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_stroke_drop_shadow(render_state, &shadow, scale, antialias);
        }
    }
}

fn render_fill_drop_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
) {
    let paint = &shadow.get_drop_shadow_paint(antialias);
    render_shadow_paint(render_state, shape, paint, SurfaceId::DropShadows);
}

// TODO: Stroke shadows
fn render_stroke_drop_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    scale: f32,
    antialias: bool,
) {
    let shadow_paint = &shadow.to_paint(scale, antialias);

    render_state
        .surfaces
        .draw_into(SurfaceId::Strokes, SurfaceId::Shadow, Some(shadow_paint));

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

// Inner Shadows
pub fn render_inner_shadows(render_state: &mut RenderState, shape: &Shape, antialias: bool) {
    if shape.has_fills() {
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_inner_shadow(render_state, &shape, &shadow, antialias);
        }
    } else {
        let scale = render_state.get_scale();
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            render_stroke_inner_shadow(render_state, &shadow, scale, antialias);
        }
    }
}

fn render_fill_inner_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
) {
    let paint = &shadow.get_inner_shadow_paint(antialias);
    render_shadow_paint(render_state, shape, paint, SurfaceId::InnerShadows);
}

// TODO: Stroke shadows
fn render_stroke_inner_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    scale: f32,
    antialias: bool,
) {
    let shadow_paint = &shadow.to_paint(scale, antialias);

    render_state
        .surfaces
        .draw_into(SurfaceId::Strokes, SurfaceId::Shadow, Some(shadow_paint));

    render_state.surfaces.draw_into(
        SurfaceId::Shadow,
        SurfaceId::Overlay,
        Some(&skia::Paint::default()),
    );

    render_state
        .surfaces
        .canvas(SurfaceId::Shadow)
        .clear(skia::Color::TRANSPARENT);
}

fn render_shadow_paint(
    render_state: &mut RenderState,
    shape: &Shape,
    paint: &Paint,
    surface_id: SurfaceId,
) {
    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            render_state.surfaces.draw_rect_to(surface_id, shape, paint);
        }
        Type::Circle => {
            render_state
                .surfaces
                .draw_circle_to(surface_id, shape, paint);
        }
        Type::Path(_) | Type::Bool(_) => {
            render_state.surfaces.draw_path_to(surface_id, shape, paint);
        }
        _ => {}
    }
}
