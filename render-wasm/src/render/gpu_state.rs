use crate::error::{Error, Result};
use skia_safe::gpu::{self, gl::FramebufferInfo, gl::TextureInfo, DirectContext};
use skia_safe::{self as skia, AlphaType, ColorType, ISize, ImageInfo};

/// Upper bound for any on-demand scratch texture we allocate (e.g. the
/// dynamically-grown Filter surface used by the texture/scatter path).
/// Capped at 8192 so a runaway allocation can't exhaust GPU memory; most
/// desktop and mobile GPUs past ~2015 support this.
pub const MAX_SCRATCH_TEXTURE_SIZE: i32 = 8192;

#[derive(Debug, Clone)]
pub struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
    /// `GL_MAX_TEXTURE_SIZE` queried once at init, capped at
    /// `MAX_SCRATCH_TEXTURE_SIZE`. See `max_scratch_texture_size()`.
    max_scratch_size: i32,
}

impl GpuState {
    pub fn try_new() -> Result<Self> {
        let interface = gpu::gl::Interface::new_native().ok_or(Error::CriticalError(
            "Failed to create GL interface".to_string(),
        ))?;
        let context = gpu::direct_contexts::make_gl(interface, None).ok_or(
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

        // Query GL_MAX_TEXTURE_SIZE once; clamp to MAX_SCRATCH_TEXTURE_SIZE.
        let hw_max_texture_size: i32 = unsafe {
            let mut v: gl::types::GLint = 0;
            gl::GetIntegerv(gl::MAX_TEXTURE_SIZE, &mut v);
            v
        };
        let queried = if hw_max_texture_size > 0 {
            hw_max_texture_size
        } else {
            4096
        };
        let max_scratch_size = queried.min(MAX_SCRATCH_TEXTURE_SIZE);

        let state = GpuState {
            context,
            framebuffer_info,
            max_scratch_size,
        };

        Ok(state)
    }

    /// Max side length we'll allocate for a temporary scratch texture.
    /// Used by the texture/scatter path to grow the Filter surface when a
    /// shape's (viewport-clipped) extrect exceeds the pre-allocated size.
    pub fn max_scratch_texture_size(&self) -> i32 {
        self.max_scratch_size
    }

    fn create_webgl_texture(&mut self, width: i32, height: i32) -> gl::types::GLuint {
        let mut texture_id: gl::types::GLuint = 0;

        unsafe {
            gl::GenTextures(1, &mut texture_id);
            gl::BindTexture(gl::TEXTURE_2D, texture_id);

            gl::TexParameteri(gl::TEXTURE_2D, gl::TEXTURE_MIN_FILTER, gl::LINEAR as i32);
            gl::TexParameteri(gl::TEXTURE_2D, gl::TEXTURE_MAG_FILTER, gl::LINEAR as i32);
            gl::TexParameteri(gl::TEXTURE_2D, gl::TEXTURE_WRAP_S, gl::CLAMP_TO_EDGE as i32);
            gl::TexParameteri(gl::TEXTURE_2D, gl::TEXTURE_WRAP_T, gl::CLAMP_TO_EDGE as i32);

            gl::TexImage2D(
                gl::TEXTURE_2D,
                0,
                gl::RGBA8 as i32,
                width,
                height,
                0,
                gl::RGBA,
                gl::UNSIGNED_BYTE,
                std::ptr::null(),
            );
        }

        texture_id
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
        label: String,
        width: i32,
        height: i32,
    ) -> Result<skia::Surface> {
        let backend_texture = unsafe {
            let texture_id = self.create_webgl_texture(width, height);
            let texture_info = TextureInfo {
                target: gl::TEXTURE_2D,
                id: texture_id,
                format: gl::RGBA8,
                protected: skia::gpu::Protected::No,
            };
            gpu::backend_textures::make_gl((width, height), gpu::Mipmapped::No, texture_info, label)
        };

        let surface = gpu::surfaces::wrap_backend_texture(
            &mut self.context,
            &backend_texture,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            skia::ColorType::RGBA8888,
            None,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create Skia surface".to_string(),
        ))?;

        Ok(surface)
    }

    /// Create a budgeted GPU surface (RGBA8). Skia fully owns and manages
    /// the backing texture — it will be tracked in the resource cache and
    /// freed automatically when no longer referenced.
    pub fn create_budgeted_surface(
        &mut self,
        width: i32,
        height: i32,
    ) -> Result<skia::Surface> {
        let info = ImageInfo::new(
            (width, height),
            ColorType::RGBA8888,
            AlphaType::Premul,
            None,
        );
        gpu::ganesh::surface_ganesh::render_target(
            &mut self.context,
            gpu::Budgeted::Yes,
            &info,
            None,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            None,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create budgeted RGBA8 surface".to_string(),
        ))
    }

    /// Create a budgeted GPU surface with F16 color type. Skia fully owns
    /// the backing texture — no manual GL texture management needed.
    pub fn create_budgeted_surface_f16(
        &mut self,
        width: i32,
        height: i32,
    ) -> Result<skia::Surface> {
        let info = ImageInfo::new(
            (width, height),
            ColorType::RGBAF16,
            AlphaType::Premul,
            None,
        );
        gpu::ganesh::surface_ganesh::render_target(
            &mut self.context,
            gpu::Budgeted::Yes,
            &info,
            None,
            gpu::SurfaceOrigin::BottomLeft,
            None,
            None,
            None,
        )
        .ok_or(Error::CriticalError(
            "Failed to create budgeted F16 surface".to_string(),
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
