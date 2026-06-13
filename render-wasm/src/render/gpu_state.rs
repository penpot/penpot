use crate::error::{Error, Result};
use skia_safe::gpu::{
    self, ganesh::context_options::Enable, gl::FramebufferInfo, ContextOptions, DirectContext,
};
use skia_safe::{self as skia, ISize};

const MIN_MAX_TEXTURE_SIZE: i32 = 512;
const MAX_MAX_TEXTURE_SIZE: i32 = 8192 * 2;

/// GPU resource-cache budget for Skia-managed (budgeted) render targets.
const RESOURCE_CACHE_LIMIT_BYTES: usize = 512 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

impl GpuState {
    pub fn try_new() -> Result<Self> {
        let interface = gpu::gl::Interface::new_native().ok_or(Error::CriticalError(
            "Failed to create GL interface".to_string(),
        ))?;

        // We tweak some options to enhance performance.
        let mut context_options = ContextOptions::default();
        // context_options.reduce_ops_task_splitting = Enable::Yes;
        context_options.skip_gl_error_checks = Enable::Yes;
        // context_options.runtime_program_cache_size = 1024;
        // context_options.allow_multiple_glyph_cache_textures = Enable::Yes;
        // context_options.allow_path_mask_caching = false;

        let mut context = gpu::direct_contexts::make_gl(interface, Some(&context_options)).ok_or(
            Error::CriticalError("Failed to create GL context".to_string()),
        )?;

        // Skia-managed render targets are budgeted against the GPU resource
        // cache. The default cap is 256 MB (GrResourceCache kDefaultMaxSize),
        // which a single atlas-sized transient can exhaust on its own. Raise it
        // so freed atlas/snapshot textures recycle as scratch instead of being
        // re-allocated from the driver every frame.
        context.set_resource_cache_limit(RESOURCE_CACHE_LIMIT_BYTES);

        let framebuffer_info = {
            let mut fboid: gl::types::GLint = 0;
            unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

            FramebufferInfo {
                fboid: fboid.try_into().map_err(|_| {
                    Error::CriticalError("Failed to convert GL framebuffer ID to u32".to_string())
                })?,
                format: gpu::gl::Format::RGBA8.into(),
                protected: gpu::Protected::No,
            }
        };

        Ok(Self {
            context,
            framebuffer_info,
        })
    }

    pub fn max_texture_size(&self) -> i32 {
        self.context
            .max_texture_size()
            .clamp(MIN_MAX_TEXTURE_SIZE, MAX_MAX_TEXTURE_SIZE)
    }

    pub fn create_surface_with_isize(
        &mut self,
        label: String,
        size: ISize,
    ) -> Result<skia::Surface> {
        self.create_surface_with_dimensions(label, size.width, size.height)
    }

    pub fn create_surface_with_dimensions(
        &mut self,
        _label: String,
        width: i32,
        height: i32,
    ) -> Result<skia::Surface> {
        // Skia-managed render target (not a wrapped client texture): snapshots
        // take the cheap COW-guarded path (`fCachedImage`, dropped for free on
        // the next write when uniquely held) instead of scheduling an eager
        // full-texture copy at snapshot time. See draw-atlas-analysis Part III.
        let image_info = skia::ImageInfo::new(
            (width, height),
            skia::ColorType::RGBA8888,
            skia::AlphaType::Premul,
            None,
        );

        let surface = gpu::surfaces::render_target(
            &mut self.context,
            gpu::Budgeted::Yes,
            &image_info,
            None,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            false,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create Skia surface".to_string(),
        ))?;

        Ok(surface)
    }

    /// Create a Skia surface that will be used for rendering.
    pub fn create_target_surface(&mut self, width: i32, height: i32) -> Result<skia::Surface> {
        let backend_render_target =
            gpu::backend_render_targets::make_gl((width, height), 1, 8, self.framebuffer_info);

        let surface = gpu::surfaces::wrap_backend_render_target(
            &mut self.context,
            &backend_render_target,
            gpu::SurfaceOrigin::BottomLeft,
            skia::ColorType::RGBA8888,
            None,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create Skia surface".to_string(),
        ))?;

        Ok(surface)
    }
}
