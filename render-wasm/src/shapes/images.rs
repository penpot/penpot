use crate::math;
use skia_safe as skia;

pub type Image = skia::Image;

pub fn draw_image_in_container(
    canvas: &skia::Canvas,
    image: &Image,
    size: (f32, f32),
    container: skia::Rect,
    paint: &skia::Paint,
) {
    let width = size.0;
    let height = size.1;
    let image_aspect_ratio = width / height;

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
    canvas.clip_rect(container, skia::ClipOp::Intersect, true);

    // Draw the image with the calculated destination rectangle
    canvas.draw_image_rect(image, None, dest_rect, &paint);

    // Restore the canvas to remove the clipping
    canvas.restore();
}
