use skia_safe::{self as skia, RRect};

use super::{RenderState, SurfaceId};
use crate::math::Rect;
use crate::shapes::{Fill, ImageFill, Shape, Type};

fn draw_image_fill_in_container(
    render_state: &mut RenderState,
    shape: &Shape,
    fill: &Fill,
    image_fill: &ImageFill,
) {
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image_fill.size();
    let canvas = render_state.surfaces.canvas(SurfaceId::Fills);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();
    let paint = fill.to_paint(container);

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

    let dest_rect = Rect::from_xywh(
        container.left - (scaled_width - container_width) / 2.0,
        container.top - (scaled_height - container_height) / 2.0,
        scaled_width,
        scaled_height,
    );

    // Save the current canvas state
    canvas.save();

    // Set the clipping rectangle to the container bounds
    match &shape.shape_type {
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
        Type::Text(_) => unimplemented!("todo"),
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
pub fn render(render_state: &mut RenderState, shape: &Shape, fill: &Fill) {
    let canvas = render_state.surfaces.canvas(SurfaceId::Fills);
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();

    match (fill, &shape.shape_type) {
        (Fill::Image(image_fill), _) => {
            draw_image_fill_in_container(render_state, shape, fill, image_fill);
        }
        (_, Type::Rect(_) | Type::Frame(_)) => {
            if let Some(corners) = shape.shape_type.corners() {
                let rrect = RRect::new_rect_radii(selrect, &corners);
                canvas.draw_rrect(rrect, &fill.to_paint(&selrect));
            } else {
                canvas.draw_rect(selrect, &fill.to_paint(&selrect));
            }
        }
        (_, Type::Circle) => {
            canvas.draw_oval(selrect, &fill.to_paint(&selrect));
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
                    canvas.draw_path(&skia_path, &fill.to_paint(&selrect));
                }
            }
        }
        (_, _) => unreachable!("This shape should not have fills"),
    }
}
