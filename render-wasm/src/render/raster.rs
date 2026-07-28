use skia_safe as skia;

use crate::error::{Error, Result};
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;
use macros::ToJs;

use super::vector;
use super::RenderResources;

/// Encoded output format for [`render_to_raster`] and `render_shape_pixels`.
/// `ToJs` publishes the discriminants to `api/shared.js`, so the CLJS side
/// reads them from here instead of hardcoding the codes.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, ToJs)]
pub enum RasterFormat {
    Png = 0,
    Jpeg = 1,
    Webp = 2,
}

impl RasterFormat {
    /// Maps the wire code sent by the CLJS hosts.
    pub fn from_u32(value: u32) -> Result<Self> {
        match value {
            0 => Ok(Self::Png),
            1 => Ok(Self::Jpeg),
            2 => Ok(Self::Webp),
            _ => Err(Error::CriticalError(format!(
                "Unknown raster format: {value}"
            ))),
        }
    }

    pub fn encoded(self) -> skia::EncodedImageFormat {
        match self {
            Self::Png => skia::EncodedImageFormat::PNG,
            Self::Jpeg => skia::EncodedImageFormat::JPEG,
            Self::Webp => skia::EncodedImageFormat::WEBP,
        }
    }

    /// Encoder quality. Mirrors the Playwright backend (`bitmap.cljs`): jpeg
    /// screenshots default to 95, webp is converted with `-quality 100`.
    /// Ignored by the PNG encoder.
    pub fn quality(self) -> u32 {
        match self {
            Self::Jpeg => 95,
            _ => 100,
        }
    }

    /// Opaque backdrop this format requires, or `None` when it keeps alpha.
    /// JPEG has no alpha channel, so transparent regions must be flattened onto
    /// white — Playwright ignores `omitBackground` for jpeg and does the same.
    /// PNG/WEBP keep transparency.
    pub fn opaque_background(self) -> Option<skia::Color> {
        match self {
            Self::Jpeg => Some(skia::Color::WHITE),
            _ => None,
        }
    }

    /// Colour to clear a fresh export surface with.
    pub fn clear_color(self) -> skia::Color {
        self.opaque_background().unwrap_or(skia::Color::TRANSPARENT)
    }
}

/// Renders a shape tree to encoded image bytes on a CPU raster surface (no
/// GPU/WebGL). Returns `(encoded_bytes, width_px, height_px)`.
pub fn render_to_raster(
    shared: &mut RenderResources,
    id: &Uuid,
    tree: ShapesPoolRef,
    scale: f32,
    format: RasterFormat,
) -> Result<(Vec<u8>, i32, i32)> {
    let Some(shape) = tree.get(id) else {
        return Ok((Vec::new(), 0, 0));
    };
    // Boards export at their own bounds (like the classic exporter, which
    // screenshots exactly the board box): content overflowing a board lands
    // outside the page/surface and is cropped. Other shapes keep the extended
    // rect so their own shadows/blur are included.
    let bounds = if matches!(shape.shape_type, crate::shapes::Type::Frame(_)) {
        shape.selrect()
    } else {
        shape.extrect(tree, scale)
    };

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
        canvas.clear(format.clear_color());
        canvas.scale((scale, scale));
        canvas.translate((-bounds.left(), -bounds.top()));
        vector::render_tree(shared, canvas, id, tree, scale, bounds)?;
    }

    let data = surface
        .image_snapshot()
        .encode(
            None::<&mut skia::gpu::DirectContext>,
            format.encoded(),
            format.quality(),
        )
        .ok_or_else(|| Error::CriticalError(format!("{format:?} encode failed")))?;

    Ok((data.as_bytes().to_vec(), width, height))
}
