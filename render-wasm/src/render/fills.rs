use skia_safe::{self as skia, Paint, RRect};

use super::{RenderState, SurfaceId};
use crate::render::get_source_rect;
use crate::shapes::{Fill, Frame, ImageFill, Rect, Shape, Type};

fn draw_image_fill(
    render_state: &mut RenderState,
    shape: &Shape,
    image_fill: &ImageFill,
    paint: &Paint,
    antialias: bool,
) {
    let image = render_state.images.get(&image_fill.id());
    if image.is_none() {
        return;
    }

    let size = image.unwrap().dimensions();
    let canvas = render_state.surfaces.canvas(SurfaceId::Fills);
    let container = &shape.selrect;
    let path_transform = shape.to_path_transform();

    let src_rect = get_source_rect(size, container, image_fill);
    let dest_rect = container;

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
            let rrect: RRect = RRect::new_rect_radii(container, corners);
            canvas.clip_rrect(rrect, skia::ClipOp::Intersect, antialias);
        }
        Type::Rect(_) | Type::Frame(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, antialias);
        }
        Type::Circle => {
            let mut oval_path = skia::Path::new();
            oval_path.add_oval(container, None);
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, antialias);
        }
        shape_type @ (Type::Path(_) | Type::Bool(_)) => {
            if let Some(path) = shape_type.path() {
                if let Some(path_transform) = path_transform {
                    canvas.clip_path(
                        path.to_skia_path().transform(&path_transform),
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
pub fn render(render_state: &mut RenderState, shape: &Shape, fill: &Fill, antialias: bool) {
    let paint = &fill.to_paint(&shape.selrect, antialias);

    match (fill, &shape.shape_type) {
        (Fill::Image(image_fill), _) => {
            draw_image_fill(render_state, shape, image_fill, paint, antialias);
        }
        (_, Type::Rect(_) | Type::Frame(_)) => {
            render_state
                .surfaces
                .draw_rect_to(SurfaceId::Fills, shape, paint);
        }
        (_, Type::Circle) => {
            render_state
                .surfaces
                .draw_circle_to(SurfaceId::Fills, shape, paint);
        }
        (_, Type::Path(_)) | (_, Type::Bool(_)) => {
            render_state
                .surfaces
                .draw_path_to(SurfaceId::Fills, shape, paint);
        }
        (_, Type::Group(_)) => {
            // Groups can have fills but they propagate them to their children
        }
        (_, _) => {
            unreachable!("This shape should not have fills")
        }
    }
}
