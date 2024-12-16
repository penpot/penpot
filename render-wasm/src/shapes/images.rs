use crate::math;
use crate::shapes::Kind;
use skia_safe::{self as skia, Rect};

pub type Image = skia::Image;

pub fn draw_image_in_container(
    canvas: &skia::Canvas,
    image: &Image,
    size: (i32, i32),
    kind: &Kind,
    paint: &skia::Paint,
) {
    let width = size.0 as f32;
    let height = size.1 as f32;
    let image_aspect_ratio = width / height;

    let container = match kind {
        Kind::Rect(r) => r.to_owned(),
        Kind::Circle(r) => r.to_owned(),
        Kind::Path(p) => p.to_skia_path().bounds().to_owned(),
        // TODO: Implement SVGRaw bounds.
        Kind::SVGRaw(_sr) => Rect::new_empty(),
    };

    // Container size
    let container_width = container.width();
    let container_height = container.height();
    let container_aspect_ratio = container_width / container_height;

    // Calculate scale to ensure the image covers the container
    let scale = if image_aspect_ratio > container_aspect_ratio {
        // Image is widther, scale based on height to cover container
        container_height / height
    } else {
        // Image is taller, scale based on width to cover container
        container_width / width
    };

    // Scaled size of the image
    let scaled_width = width * scale;
    let scaled_height = height * scale;

    // Calculate offset to center the image in the container
    let offset_x = container.left + (container_width - scaled_width) / 2.0;
    let offset_y = container.top + (container_height - scaled_height) / 2.0;

    let dest_rect = math::Rect::from_xywh(offset_x, offset_y, scaled_width, scaled_height);

    // Save the current canvas state
    canvas.save();

    // Set the clipping rectangle to the container bounds
    match kind {
        Kind::Rect(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
        Kind::Circle(_) => {
            let mut oval_path = skia::Path::new();
            oval_path.add_oval(container, None);
            canvas.clip_path(&oval_path, skia::ClipOp::Intersect, true);
        }
        Kind::Path(p) => {
            canvas.clip_path(&p.to_skia_path(), skia::ClipOp::Intersect, true);
        }
        Kind::SVGRaw(_) => {
            canvas.clip_rect(container, skia::ClipOp::Intersect, true);
        }
    }

    // Draw the image with the calculated destination rectangle
    canvas.draw_image_rect(image, None, dest_rect, &paint);

    // Restore the canvas to remove the clipping
    canvas.restore();
}
