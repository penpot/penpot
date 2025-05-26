use super::{RenderState, SurfaceId};
use crate::render::strokes;
use crate::render::text::{self};
use crate::shapes::{Shadow, Shape, Stroke, Type};
use skia_safe::{self as skia, Paint};

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
    let paint = &shadow.get_drop_shadow_paint(antialias);
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
    let paint = &shadow.get_inner_shadow_paint(antialias);
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
                Some(SurfaceId::Strokes), // FIXME
                filter.as_ref(),
                antialias,
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
                Some(SurfaceId::Strokes), // FIXME
                filter.as_ref(),
                antialias,
            )
        }
    }
}

pub fn render_text_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
        render_text_drop_shadow(render_state, shadow, paragraphs, antialias, fonts);
    }
}

pub fn render_text_drop_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    let paint = shadow.get_drop_shadow_paint(antialias);
    text::render(
        render_state,
        paragraphs,
        Some(SurfaceId::DropShadows),
        Some(paint),
        fonts,
    );
}

pub fn render_text_stroke_drop_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    stroke: &Stroke,
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    for shadow in shape.drop_shadows().rev().filter(|s| !s.hidden()) {
        let stroke_shadow = shadow.get_drop_shadow_filter();
        strokes::render_text_paragraphs(
            render_state,
            shape,
            stroke,
            paragraphs,
            Some(SurfaceId::DropShadows),
            stroke_shadow.as_ref(),
            antialias,
            fonts,
        );
    }
}

pub fn render_text_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
        render_text_inner_shadow(render_state, shadow, paragraphs, antialias, fonts);
    }
}

pub fn render_text_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    stroke: &Stroke,
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    for shadow in shape.inner_shadows().rev().filter(|s| !s.hidden()) {
        let stroke_shadow = shadow.get_inner_shadow_filter();
        strokes::render_text_paragraphs(
            render_state,
            shape,
            stroke,
            paragraphs,
            Some(SurfaceId::InnerShadows),
            stroke_shadow.as_ref(),
            antialias,
            fonts,
        );
    }
}

pub fn render_text_inner_shadow(
    render_state: &mut RenderState,
    shadow: &Shadow,
    paragraphs: &mut Vec<(skia::textlayout::ParagraphBuilder, skia::Point, f32)>, // Updated type
    antialias: bool,
    fonts: &skia::textlayout::FontCollection,
) {
    let paint = shadow.get_inner_shadow_paint(antialias);

    text::render(
        render_state,
        paragraphs,
        Some(SurfaceId::InnerShadows),
        Some(paint),
        fonts,
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
