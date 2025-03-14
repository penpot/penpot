use skia_safe::{self as skia, Paint, RRect};

use super::{RenderState, SurfaceId};
use crate::math::Rect as MathRect;
use crate::shapes::{Fill, Frame, ImageFill, Rect, Shape, Type};

fn draw_image_fill_in_container(
    render_state: &mut RenderState,
    shape: &Shape,
    image_fill: &ImageFill,
    paint: &Paint,
) {
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image_fill.size();
    let canvas = render_state.surfaces.canvas(SurfaceId::Fills);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();

    let width = size.0 as f32;
    let height = size.1 as f32;
    let image_aspect_ratio = width / height;

    // Container size
    let container_width = container.width();
    let container_height = container.height();
    let container_aspect_ratio = container_width / container_height;

    // Calculate scale to ensure the image covers the container
    let scale = if image_aspect_ratio > container_aspect_ratio {
        // Image is wider, scale based on height to cover container
        container_height / height
    } else {
        // Image is taller, scale based on width to cover container
        container_width / width
    };

    // Scaled size of the image
    let scaled_width = width * scale;
    let scaled_height = height * scale;

    let dest_rect = MathRect::from_xywh(
        container.left - (scaled_width - container_width) / 2.0,
        container.top - (scaled_height - container_height) / 2.0,
        scaled_width,
        scaled_height,
    );

    // Save the current canvas state
    canvas.save();

    // Set the clipping rectangle to the container bounds
    match &shape.shape_type {
        Type::Rect(Rect {
            corners: Some(corners),
        })
        | Type::Frame(Frame {
            corners: Some(corners),
            ..
        }) => {
            let rrect: RRect = RRect::new_rect_radii(container, &corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, true);
        }
        Type::Rect(_) | Type::Frame(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
        Type::Circle => {
            let mut oval_path = skia::Path::new();
            oval_path.add_oval(container, None);
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, true);
        }
        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(path) = shape_type.path() {
                if let Some(path_transform) = path_transform {
                    canvas.clip_path(
                        &path.to_skia_path().transform(&path_transform),
                        skia::ClipOp::Intersect,
                        true,
                    );
                }
            }
        }
        Type::SVGRaw(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
        Type::Group(_) => unreachable!("A group should not have fills"),
        Type::Text(_) => unimplemented!("TODO"),
    }

    // Draw the image with the calculated destination rectangle
    if let Some(image) = image {
        canvas.draw_image_rect_with_sampling_options(
            image,
            None,
            dest_rect,
            render_state.sampling_options,
            &paint,
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
    fill: &Fill,
    shadow_paint: Option<&Paint>,
) {
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();

    let (paint, surface_id) = if let Some(paint) = shadow_paint {
        (paint, SurfaceId::Shadow)
    } else {
        (&fill.to_paint(&selrect), SurfaceId::Fills)
    };

    match (fill, &shape.shape_type) {
        (Fill::Image(image_fill), _) if shadow_paint.is_none() => {
            draw_image_fill_in_container(render_state, shape, image_fill, paint);
        }
        (_, Type::Rect(_) | Type::Frame(_)) => {
            if let Some(corners) = shape.shape_type.corners() {
                let rrect = RRect::new_rect_radii(selrect, &corners);
                let canvas = render_state.surfaces.canvas(surface_id);
                canvas.draw_rrect(rrect, paint);
            } else {
                let canvas = render_state.surfaces.canvas(surface_id);
                canvas.draw_rect(selrect, paint);
            }
        }
        (_, Type::Circle) => {
            let canvas = render_state.surfaces.canvas(surface_id);
            canvas.draw_oval(selrect, paint);
        }
        (_, Type::Path(_)) | (_, Type::Bool(_)) => {
            if let Some(path) = &shape.shape_type.path() {
                let svg_attrs = &shape.svg_attrs;
                let mut skia_path = &mut path.to_skia_path();

                if let Some(path_transform) = path_transform {
                    skia_path = skia_path.transform(&path_transform);
                    if let Some("evenodd") = svg_attrs.get("fill-rule").map(String::as_str) {
                        skia_path.set_fill_type(skia::PathFillType::EvenOdd);
                    }
                    let canvas = render_state.surfaces.canvas(surface_id);
                    canvas.draw_path(&skia_path, paint);
                }
            }
        }
        (_, _) => {
            if shadow_paint.is_none() {
                unreachable!("This shape should not have fills")
            }
        }
    }
}
