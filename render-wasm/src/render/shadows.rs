use super::{RenderState, SurfaceId};
use crate::render::strokes;
use crate::render::text::{self};
use crate::shapes::{Shadow, Shape, Stroke, Type};
use skia_safe::textlayout::ParagraphBuilder;
use skia_safe::{Paint, Path};

// Fill Shadows
pub fn render_fill_drop_shadows(render_state: &mut RenderState, shape: &Shape, antialias: bool) {
    if shape.has_fills() {
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_drop_shadow(render_state, shape, shadow, antialias);
        }
    }
}

fn render_fill_drop_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
) {
    let paint = &shadow.get_drop_shadow_paint(antialias, shape.image_filter(1.).as_ref());
    render_shadow_paint(render_state, shape, paint, SurfaceId::DropShadows);
}

pub fn render_fill_inner_shadows(render_state: &mut RenderState, shape: &Shape, antialias: bool) {
    if shape.has_fills() {
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            render_fill_inner_shadow(render_state, shape, shadow, antialias);
        }
    }
}

fn render_fill_inner_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
) {
    let paint = &shadow.get_inner_shadow_paint(antialias, shape.image_filter(1.).as_ref());
    render_shadow_paint(render_state, shape, paint, SurfaceId::InnerShadows);
}

pub fn render_stroke_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    antialias: bool,
) {
    if !shape.has_fills() {
        for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
            let filter = shadow.get_drop_shadow_filter();
            strokes::render(
                render_state,
                shape,
                stroke,
                None,
                filter.as_ref(),
                None,
                antialias,
                None,
            )
        }
    }
}

pub fn render_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    antialias: bool,
) {
    if !shape.has_fills() {
        for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
            let filter = shadow.get_inner_shadow_filter();
            strokes::render(
                render_state,
                shape,
                stroke,
                None,
                filter.as_ref(),
                None,
                antialias,
                None,
            )
        }
    }
}

pub fn render_text_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    antialias: bool,
) {
    for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
        render_text_drop_shadow(render_state, shape, shadow, paragraphs, antialias);
    }
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_path_stroke_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paths: &Vec<(Path, Paint)>,
    stroke: &Stroke,
    antialias: bool,
) {
    for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
        let stroke_shadow = shadow.get_drop_shadow_filter();
        strokes::render_text_paths(
            render_state,
            shape,
            stroke,
            paths,
            Some(SurfaceId::DropShadows),
            stroke_shadow.as_ref(),
            antialias,
        );
    }
}

pub fn render_text_drop_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    antialias: bool,
) {
    let paint = shadow.get_drop_shadow_paint(antialias, shape.image_filter(1.).as_ref());

    text::render(
        render_state,
        shape,
        paragraphs,
        Some(SurfaceId::DropShadows),
        Some(&paint),
    );
}

pub fn render_text_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    antialias: bool,
) {
    for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
        render_text_inner_shadow(render_state, shape, shadow, paragraphs, antialias);
    }
}

pub fn render_text_inner_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    paragraphs: &mut [Vec<ParagraphBuilder>],
    antialias: bool,
) {
    let paint = shadow.get_inner_shadow_paint(antialias, shape.image_filter(1.).as_ref());

    text::render(
        render_state,
        shape,
        paragraphs,
        Some(SurfaceId::InnerShadows),
        Some(&paint),
    );
}

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_path_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paths: &Vec<(Path, Paint)>,
    stroke: &Stroke,
    antialias: bool,
) {
    for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
        let stroke_shadow = shadow.get_inner_shadow_filter();
        strokes::render_text_paths(
            render_state,
            shape,
            stroke,
            paths,
            Some(SurfaceId::InnerShadows),
            stroke_shadow.as_ref(),
            antialias,
        );
    }
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
