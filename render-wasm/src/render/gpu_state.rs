use skia_safe as skia;
use skia_safe::gpu::{self, gl::FramebufferInfo, DirectContext};

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
