use skia_safe as skia;

use crate::error::Result;

use super::{FontStore, ImageStore};

/// Render dependencies that the export (PDF) path needs independently of the
/// surface/tile/GPU machinery in [`RenderState`]: the font store, the
/// decoded-image store, and the default sampling options.
///
/// Splitting these out keeps the vector/PDF export path (which takes
/// `&mut RenderResources`) decoupled from the interactive renderer, and gives a
/// single source of truth reached through `get_resources()`.
pub(crate) struct RenderResources {
    pub fonts: FontStore,
    pub images: ImageStore,
    pub sampling_options: skia::SamplingOptions,
}

impl RenderResources {
    pub fn try_new() -> Result<Self> {
        Ok(Self {
            fonts: FontStore::try_new()?,
            images: ImageStore::new(),
            sampling_options: skia::SamplingOptions::new(
                skia::FilterMode::Linear,
                skia::MipmapMode::Nearest,
            ),
        })
    }

    /// Headless export resources: CPU-only image store, no GPU/WebGL.
    pub fn try_new_headless() -> Result<Self> {
        Ok(Self {
            fonts: FontStore::try_new()?,
            images: ImageStore::new_without_gpu(),
            sampling_options: skia::SamplingOptions::new(
                skia::FilterMode::Linear,
                skia::MipmapMode::Nearest,
            ),
        })
    }
}
