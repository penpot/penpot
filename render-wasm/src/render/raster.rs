use skia_safe as skia;

use crate::error::{Error, Result};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

use super::vector;
use super::RenderResources;

/// Renders a shape tree to PNG bytes on a CPU raster surface (no GPU/WebGL).
/// Returns `(png_bytes, width_px, height_px)`.
pub fn render_to_raster(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
) -> Result<(Vec<u8>, i32, i32)> {
    let Some(shape) = tree.get(id) else {
        return Ok((Vec::new(), 0, 0));
    };
    let bounds = shape.extrect(tree, scale);

    let width = (bounds.width() * scale).ceil() as i32;
    let height = (bounds.height() * scale).ceil() as i32;
    if width <= 0 || height <= 0 {
        return Ok((Vec::new(), 0, 0));
    }

    let mut surface = skia::surfaces::raster_n32_premul((width, height)).ok_or_else(|| {
        Error::CriticalError("Failed to create raster export surface".to_string())
    })?;

    {
        let canvas = surface.canvas();
        canvas.clear(skia::Color::TRANSPARENT);
        canvas.scale((scale, scale));
        canvas.translate((-bounds.left(), -bounds.top()));
        vector::render_tree(shared, canvas, id, tree, scale)?;
    }

    let data = surface
        .image_snapshot()
        .encode(
            None::<&mut skia::gpu::DirectContext>,
            skia::EncodedImageFormat::PNG,
            100,
        )
        .ok_or_else(|| Error::CriticalError("PNG encode failed".to_string()))?;

    Ok((data.as_bytes().to_vec(), width, height))
}
