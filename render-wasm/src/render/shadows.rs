use skia_safe::{self as skia};

use super::{RenderState, SurfaceId};
use crate::shapes::{Shadow, Shape, Type};

pub fn render_drop_shadows(render_state: &mut RenderState, shape: &Shape) {
    if shape.fills().len() > 0 {
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_drop_shadow(render_state, &shape, &shadow);
        }
    } else {
        let scale = render_state.viewbox.zoom * render_state.options.dpr();
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_stroke_drop_shadow(render_state, &shadow, scale);
        }
    }
}

fn render_fill_drop_shadow(render_state: &mut RenderState, shape: &Shape, shadow: &Shadow) {
    let paint = &shadow.get_drop_shadow_paint();

    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            render_state
                .surfaces
                .draw_rect_to(SurfaceId::DropShadows, shape, paint);
        }
        Type::Circle => {
            render_state
                .surfaces
                .draw_circle_to(SurfaceId::DropShadows, shape, paint);
        }
        Type::Path(_) | Type::Bool(_) => {
            render_state
                .surfaces
                .draw_path_to(SurfaceId::DropShadows, shape, paint);
        }
        _ => {}
    }
}

fn render_stroke_drop_shadow(render_state: &mut RenderState, shadow: &Shadow, scale: f32) {
    let shadow_paint = &shadow.to_paint(scale);

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
