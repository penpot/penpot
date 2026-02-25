use skia_safe::{self as skia, Paint, RRect};

use super::{filters, RenderState, SurfaceId};
use crate::render::get_source_rect;
use crate::shapes::{merge_fills, Fill, Frame, ImageFill, Rect, Shape, Type};

fn draw_image_fill(
    render_state: &mut RenderState,
    shape: &Shape,
    image_fill: &ImageFill,
    paint: &Paint,
    antialias: bool,
    surface_id: SurfaceId,
) {
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image.unwrap().dimensions();
    let canvas = render_state.surfaces.canvas_and_mark_dirty(surface_id);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();

    let src_rect = get_source_rect(size, container, image_fill);
    let dest_rect = container;

    let mut image_paint = skia::Paint::default();
    image_paint.set_anti_alias(antialias);
    if let Some(filter) = shape.image_filter(1.) {
        image_paint.set_image_filter(filter.clone());
    }

    let layer_rec = skia::canvas::SaveLayerRec::default().paint(&image_paint);
    // Save the current canvas state
    canvas.save_layer(&layer_rec);

    // Set the clipping rectangle to the container bounds
    match &shape.shape_type {
        Type::Rect(Rect {
            corners: Some(corners),
        })
        | Type::Frame(Frame {
            corners: Some(corners),
            ..
        }) => {
            let rrect: RRect = RRect::new_rect_radii(container, corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
        }
        Type::Rect(_) | Type::Frame(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, antialias);
        }
        Type::Circle => {
            let oval_path = {
                let mut pb = skia::PathBuilder::new();
                pb.add_oval(container, None, None);
                pb.detach()
            };
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, antialias);
        }
        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(path) = shape_type.path() {
                if let Some(path_transform) = path_transform {
                    canvas.clip_path(
                        &path.to_skia_path().make_transform(&path_transform),
                        skia::ClipOp::Intersect,
                        antialias,
                    );
                }
            }
        }
        Type::SVGRaw(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, antialias);
        }
        Type::Group(_) => unreachable!("A group should not have fills"),
        Type::Text(_) => unimplemented!("TODO"),
    }

    // Draw the image with the calculated destination rectangle
    if let Some(image) = image {
        canvas.draw_image_rect_with_sampling_options(
            image,
            Some((&src_rect, skia::canvas::SrcRectConstraint::Strict)),
            dest_rect,
            render_state.sampling_options,
            paint,
        );
    }

    // Restore the canvas to remove the clipping
    canvas.restore();
}

/**
 * This SHOULD be the only public function in this module.
 */
pub fn render(
    render_state: &mut RenderState,
    shape: &Shape,
    fills: &[Fill],
    antialias: bool,
    surface_id: SurfaceId,
    spread: Option<f32>,
) {
    if fills.is_empty() {
        return;
    }

    // Image fills use draw_image_fill which needs render_state for GPU images
    // and sampling options that get_fill_shader (used by merge_fills) lacks.
    let has_image_fills = fills.iter().any(|f| matches!(f, Fill::Image(_)));
    if has_image_fills {
        for fill in fills.iter().rev() {
            render_single_fill(render_state, shape, fill, antialias, surface_id, spread);
        }
        return;
    }

    let mut paint = merge_fills(fills, shape.selrect);
    paint.set_anti_alias(antialias);

    if let Some(image_filter) = shape.image_filter(1.) {
        let bounds = image_filter.compute_fast_bounds(shape.selrect);
        if filters::render_with_filter_surface(
            render_state,
            bounds,
            surface_id,
            |state, temp_surface| {
                let mut filtered_paint = paint.clone();
                filtered_paint.set_image_filter(image_filter.clone());
                draw_fill_to_surface(state, shape, temp_surface, &filtered_paint, spread);
            },
        ) {
            return;
        } else {
            paint.set_image_filter(image_filter);
        }
    }

    draw_fill_to_surface(render_state, shape, surface_id, &paint, spread);
}

/// Draws a single paint (with a merged shader) to the appropriate surface
/// based on the shape type.
fn draw_fill_to_surface(
    render_state: &mut RenderState,
    shape: &Shape,
    surface_id: SurfaceId,
    paint: &Paint,
    spread: Option<f32>,
) {
    match &shape.shape_type {
        Type::Rect(_) | Type::Frame(_) => {
            render_state
                .surfaces
                .draw_rect_to(surface_id, shape, paint, spread);
        }
        Type::Circle => {
            render_state
                .surfaces
                .draw_circle_to(surface_id, shape, paint, spread);
        }
        Type::Path(_) | Type::Bool(_) => {
            render_state
                .surfaces
                .draw_path_to(surface_id, shape, paint, spread);
        }
        Type::Group(_) => {}
        _ => unreachable!("This shape should not have fills"),
    }
}

fn render_single_fill(
    render_state: &mut RenderState,
    shape: &Shape,
    fill: &Fill,
    antialias: bool,
    surface_id: SurfaceId,
    spread: Option<f32>,
) {
    let mut paint = fill.to_paint(&shape.selrect, antialias);
    if let Some(image_filter) = shape.image_filter(1.) {
        let bounds = image_filter.compute_fast_bounds(shape.selrect);
        if filters::render_with_filter_surface(
            render_state,
            bounds,
            surface_id,
            |state, temp_surface| {
                let mut filtered_paint = paint.clone();
                filtered_paint.set_image_filter(image_filter.clone());
                draw_single_fill_to_surface(
                    state,
                    shape,
                    fill,
                    antialias,
                    temp_surface,
                    &filtered_paint,
                    spread,
                );
            },
        ) {
            return;
        } else {
            paint.set_image_filter(image_filter);
        }
    }

    draw_single_fill_to_surface(
        render_state,
        shape,
        fill,
        antialias,
        surface_id,
        &paint,
        spread,
    );
}

fn draw_single_fill_to_surface(
    render_state: &mut RenderState,
    shape: &Shape,
    fill: &Fill,
    antialias: bool,
    surface_id: SurfaceId,
    paint: &Paint,
    spread: Option<f32>,
) {
    match (fill, &shape.shape_type) {
        (Fill::Image(image_fill), _) => {
            draw_image_fill(
                render_state,
                shape,
                image_fill,
                paint,
                antialias,
                surface_id,
            );
        }
        (_, Type::Rect(_) | Type::Frame(_)) => {
            render_state
                .surfaces
                .draw_rect_to(surface_id, shape, paint, spread);
        }
        (_, Type::Circle) => {
            render_state
                .surfaces
                .draw_circle_to(surface_id, shape, paint, spread);
        }
        (_, Type::Path(_)) | (_, Type::Bool(_)) => {
            render_state
                .surfaces
                .draw_path_to(surface_id, shape, paint, spread);
        }
        (_, Type::Group(_)) => {
            // Groups can have fills but they propagate them to their children
        }
        _ => unreachable!("This shape should not have fills"),
    }
}
