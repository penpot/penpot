use crate::{
    math,
    shapes::{Fill, ImageFill, Kind, Shape},
};
use skia_safe::{self as skia, RRect};

use super::RenderState;

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
    let canvas = render_state.drawing_surface.canvas();
    let kind = &shape.kind;
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

    let dest_rect = math::Rect::from_xywh(
        container.left - (scaled_width - container_width) / 2.0,
        container.top - (scaled_height - container_height) / 2.0,
        scaled_width,
        scaled_height,
    );

    // Save the current canvas state
    canvas.save();

    // Set the clipping rectangle to the container bounds
    match kind {
        Kind::Rect(_, _) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
        Kind::Circle(_) => {
            let mut oval_path = skia::Path::new();
            oval_path.add_oval(container, None);
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, true);
        }
        Kind::Path(path) | Kind::Bool(_, path) => {
            canvas.clip_path(
                &path.to_skia_path().transform(&path_transform.unwrap()),
                skia::ClipOp::Intersect,
                true,
            );
        }
        Kind::SVGRaw(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
    }

    // Draw the image with the calculated destination rectangle
    canvas.draw_image_rect(image.unwrap(), None, dest_rect, &paint);

    // Restore the canvas to remove the clipping
    canvas.restore();
}

/**
 * This SHOULD be the only public function in this module.
 */
pub fn render(render_state: &mut RenderState, shape: &Shape, fill: &Fill) {
    let canvas = render_state.drawing_surface.canvas();
    let selrect = shape.selrect;
    let path_transform = shape.to_path_transform();
    let kind = &shape.kind;
    match (fill, kind) {
        (Fill::Image(image_fill), _) => {
            draw_image_fill_in_container(render_state, shape, fill, image_fill);
        }
        (_, Kind::Rect(rect, None)) => {
            canvas.draw_rect(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Rect(rect, Some(corners))) => {
            let rrect = RRect::new_rect_radii(rect, &corners);
            canvas.draw_rrect(rrect, &fill.to_paint(&selrect));
        }
        (_, Kind::Circle(rect)) => {
            canvas.draw_oval(rect, &fill.to_paint(&selrect));
        }
        (_, Kind::Path(path)) | (_, Kind::Bool(_, path)) => {
            let svg_attrs = &shape.svg_attrs;
            let mut skia_path = &mut path.to_skia_path();
            skia_path = skia_path.transform(&path_transform.unwrap());
            if let Some("evenodd") = svg_attrs.get("fill-rule").map(String::as_str) {
                skia_path.set_fill_type(skia::PathFillType::EvenOdd);
            }
            canvas.draw_path(&skia_path, &fill.to_paint(&selrect));
        }
        (_, _) => todo!(),
    }
}
