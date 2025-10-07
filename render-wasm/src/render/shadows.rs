use super::{RenderState, SurfaceId};
use crate::render::strokes;
use crate::shapes::{ParagraphBuilderGroup, Shadow, Shape, Stroke, Type};
use skia_safe::{canvas::SaveLayerRec, Paint, Path};

use crate::render::text;

// Fill Shadows
pub fn render_fill_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    antialias: bool,
    surface_id: SurfaceId,
) {
    if shape.has_fills() {
        for shadow in shape.inner_shadows_visible() {
            render_fill_inner_shadow(render_state, shape, shadow, antialias, surface_id);
        }
    }
}

fn render_fill_inner_shadow(
    render_state: &mut RenderState,
    shape: &Shape,
    shadow: &Shadow,
    antialias: bool,
    surface_id: SurfaceId,
) {
    let paint = &shadow.get_inner_shadow_paint(antialias, shape.image_filter(1.).as_ref());
    render_shadow_paint(render_state, shape, paint, surface_id);
}

pub fn render_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    stroke: &Stroke,
    antialias: bool,
    surface_id: SurfaceId,
) {
    if !shape.has_fills() {
        for shadow in shape.inner_shadows_visible() {
            let filter = shadow.get_inner_shadow_filter();
            strokes::render(
                render_state,
                shape,
                stroke,
                Some(surface_id),
                filter.as_ref(),
                antialias,
            )
        }
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
    for shadow in shape.drop_shadows_visible() {
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

// Render text paths (unused)
#[allow(dead_code)]
pub fn render_text_path_stroke_inner_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paths: &Vec<(Path, Paint)>,
    stroke: &Stroke,
    antialias: bool,
) {
    for shadow in shape.inner_shadows_visible() {
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

pub fn render_text_shadows(
    render_state: &mut RenderState,
    shape: &Shape,
    paragraphs: &mut [ParagraphBuilderGroup],
    stroke_paragraphs_group: &mut [Vec<ParagraphBuilderGroup>],
    surface_id: Option<SurfaceId>,
    shadows: &[Paint],
    blur_filter: &Option<skia_safe::ImageFilter>,
) {
    if stroke_paragraphs_group.is_empty() {
        return;
    }

    let canvas = render_state
        .surfaces
        .canvas(surface_id.unwrap_or(SurfaceId::TextDropShadows));

    for shadow in shadows {
        let shadow_layer = SaveLayerRec::default().paint(shadow);
        canvas.save_layer(&shadow_layer);

        text::render(
            None,
            Some(canvas),
            shape,
            paragraphs,
            surface_id,
            None,
            blur_filter.as_ref(),
        );

        for stroke_paragraphs in stroke_paragraphs_group.iter_mut() {
            text::render(
                None,
                Some(canvas),
                shape,
                stroke_paragraphs,
                surface_id,
                None,
                blur_filter.as_ref(),
            );
        }

        canvas.restore();
    }
}
