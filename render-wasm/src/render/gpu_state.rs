use crate::error::{Error, Result};
use skia_safe::gpu::{
    self, ganesh::context_options::Enable, gl::FramebufferInfo, gl::TextureInfo, ContextOptions,
    DirectContext,
};
use skia_safe::{self as skia, ISize};

const MIN_MAX_TEXTURE_SIZE: i32 = 512;
const MAX_MAX_TEXTURE_SIZE: i32 = 4096;
/// Cap for the canvas framebuffer / backbuffer (not the tile atlas).
/// Larger than a typical viewport at DPR 2 but below sizes that would exceed
/// GPU limits when CSS dimensions are very large.
pub const MAX_SURFACE_SIZE: i32 = 8192;

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
        context_options.reduce_ops_task_splitting = Enable::No;
        context_options.skip_gl_error_checks = Enable::Yes;
        // context_options.runtime_program_cache_size = 1024;
        // context_options.allow_multiple_glyph_cache_textures = Enable::Yes;
        // context_options.allow_path_mask_caching = false;

        let context = gpu::direct_contexts::make_gl(interface, Some(&context_options)).ok_or(
            Error::CriticalError("Failed to create GL context".to_string()),
        )?;

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

    pub fn max_surface_size(&self) -> i32 {
        self.context
            .max_texture_size()
            .clamp(MIN_MAX_TEXTURE_SIZE, MAX_SURFACE_SIZE)
    }

    /// Actual default-framebuffer size after the canvas backing store is set.
    /// Browsers may allocate a smaller `drawingBuffer` than `canvas.width`;
    /// wrapping Skia at the requested size then shifts content (GL origin is
    /// bottom-left). Native builds have no canvas; return `None`.
    pub fn drawing_buffer_size(&self) -> Option<(i32, i32)> {
        #[cfg(target_arch = "wasm32")]
        {
            let w = crate::run_script_int!(
                "(typeof GLctx!=='undefined'&&GLctx)?GLctx.drawingBufferWidth:0"
            );
            let h = crate::run_script_int!(
                "(typeof GLctx!=='undefined'&&GLctx)?GLctx.drawingBufferHeight:0"
            );
            if w > 0 && h > 0 {
                return Some((w, h));
            }
        }
        let _ = self;
        None
    }

    pub fn create_surface_with_isize(&mut self, size: ISize) -> Result<skia::Surface> {
        self.create_surface_with_dimensions(size.width, size.height)
    }

    pub fn create_surface_with_dimensions(
        &mut self,
        width: i32,
        height: i32,
    ) -> Result<skia::Surface> {
        let image_info = skia::ImageInfo::new(
            (width, height),
            skia::ColorType::RGBA8888,
            skia::AlphaType::Premul,
            None,
        );

        gpu::surfaces::render_target(
            &mut self.context,
            gpu::Budgeted::No,
            &image_info,
            None,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            false,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create Skia surface".to_string(),
        ))
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

    #[allow(dead_code)]
    pub fn create_surface_from_texture(
        &mut self,
        width: i32,
        height: i32,
        texture_id: u32,
    ) -> skia::Surface {
        let texture_info = TextureInfo {
            target: gl::TEXTURE_2D,
            id: texture_id,
            format: gl::RGBA8,
            protected: skia::gpu::Protected::No,
        };

        let backend_texture = unsafe {
            gpu::backend_textures::make_gl(
                (width, height),
                gpu::Mipmapped::No,
                texture_info,
                String::from("export_texture"),
            )
        };

        gpu::surfaces::wrap_backend_texture(
            &mut self.context,
            &backend_texture,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            skia::ColorType::RGBA8888,
            None,
            None,
        )
        .unwrap()
    }
}
