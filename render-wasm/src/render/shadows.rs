use super::{RenderState, SurfaceId};
use crate::render::strokes;
use crate::shapes::{Shadow, Shape, Type};
use skia_safe::{ImageFilter, Paint};

// Drop Shadows
pub fn render_drop_shadows(render_state: &mut RenderState, shape: &Shape) {
    if shape.has_fills() {
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_drop_shadow(render_state, &shape, &shadow);
        }
    } else {
        for shadow in shape.drop_shadows().rev().filter(|s: &&Shadow| !s.hidden()) {
            render_stroke_drop_shadow(render_state, &shape, &shadow);
        }
    }
}

fn render_fill_drop_shadow(render_state: &mut RenderState, shape: &Shape, shadow: &Shadow) {
    let paint = &shadow.get_drop_shadow_paint();
    render_shadow_paint(render_state, shape, paint, SurfaceId::DropShadows);
}

pub fn render_stroke_drop_shadow(render_state: &mut RenderState, shape: &Shape, shadow: &Shadow) {
    let shadow_filter = shadow.get_drop_shadow_filter();
    render_stroke_shadow_paint(
        render_state,
        shape,
        shadow_filter.as_ref(),
        SurfaceId::DropShadows,
    );
}

pub fn render_inner_shadows(render_state: &mut RenderState, shape: &Shape) {
    if shape.has_fills() {
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_inner_shadow(render_state, &shape, &shadow);
        }
    } else {
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            render_stroke_inner_shadow(render_state, &shape, &shadow);
        }
    }
}

fn render_fill_inner_shadow(render_state: &mut RenderState, shape: &Shape, shadow: &Shadow) {
    let paint = &shadow.get_inner_shadow_paint();
    render_shadow_paint(render_state, shape, paint, SurfaceId::InnerShadows);
}

pub fn render_stroke_inner_shadow(render_state: &mut RenderState, shape: &Shape, shadow: &Shadow) {
    let shadow_filter = shadow.get_inner_shadow_filter();
    render_stroke_shadow_paint(
        render_state,
        shape,
        shadow_filter.as_ref(),
        SurfaceId::InnerShadows,
    );
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

fn render_stroke_shadow_paint(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: Option<&ImageFilter>,
    surface_id: SurfaceId,
) {
    for stroke in shape.strokes() {
        strokes::render(render_state, shape, stroke, Some(surface_id), shadow)
    }
}
