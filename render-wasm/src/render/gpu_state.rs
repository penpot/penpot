use skia_safe::gpu::{self, gl::FramebufferInfo, gl::TextureInfo, DirectContext};
use skia_safe::{self as skia, ISize};

pub struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

impl GpuState {
    pub fn new() -> Self {
        let interface = gpu::gl::Interface::new_native().unwrap();
        let context = gpu::direct_contexts::make_gl(interface, None).unwrap();
        let framebuffer_info = {
            let mut fboid: gl::types::GLint = 0;
            unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

            FramebufferInfo {
                fboid: fboid.try_into().unwrap(),
                format: gpu::gl::Format::RGBA8.into(),
                protected: gpu::Protected::No,
            }
        };

        GpuState {
            context,
            framebuffer_info,
        }
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

    pub fn create_surface_with_isize(&mut self, label: String, size: ISize) -> skia::Surface {
        self.create_surface_with_dimensions(label, size.width, size.height)
    }

    pub fn create_surface_with_dimensions(
        &mut self,
        label: String,
        width: i32,
        height: i32,
    ) -> skia::Surface {
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

    /// Create a Skia surface that will be used for rendering.
    pub fn create_target_surface(&mut self, width: i32, height: i32) -> skia::Surface {
        let backend_render_target =
            gpu::backend_render_targets::make_gl((width, height), 1, 8, self.framebuffer_info);

        gpu::surfaces::wrap_backend_render_target(
            &mut self.context,
            &backend_render_target,
            gpu::SurfaceOrigin::BottomLeft,
            skia::ColorType::RGBA8888,
            None,
            None,
        )
        .unwrap()
    }
}
