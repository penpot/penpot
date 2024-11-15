use skia_safe as skia;
use skia_safe::gpu::{self, gl::FramebufferInfo, DirectContext};

struct GpuState {
    pub context: DirectContext,
    framebuffer_info: FramebufferInfo,
}

impl GpuState {
    fn new() -> Self {
        let interface = skia_safe::gpu::gl::Interface::new_native().unwrap();
        let context = skia_safe::gpu::direct_contexts::make_gl(interface, None).unwrap();
        let framebuffer_info = {
            let mut fboid: gl::types::GLint = 0;
            unsafe { gl::GetIntegerv(gl::FRAMEBUFFER_BINDING, &mut fboid) };

            FramebufferInfo {
                fboid: fboid.try_into().unwrap(),
                format: skia_safe::gpu::gl::Format::RGBA8.into(),
                protected: skia_safe::gpu::Protected::No,
            }
        };

        GpuState {
            context,
            framebuffer_info,
        }
    }

    /// Create a Skia surface that will be used for rendering.
    fn create_target_surface(&mut self, width: i32, height: i32) -> skia::Surface {
        let backend_render_target =
            gpu::backend_render_targets::make_gl((width, height), 1, 8, self.framebuffer_info);

        gpu::surfaces::wrap_backend_render_target(
            &mut self.context,
            &backend_render_target,
            skia_safe::gpu::SurfaceOrigin::BottomLeft,
            skia_safe::ColorType::RGBA8888,
            None,
            None,
        )
        .unwrap()
    }
}

pub(crate) struct RenderState {
    gpu_state: GpuState,
    pub final_surface: skia::Surface,
    pub drawing_surface: skia::Surface,
}

impl RenderState {
    pub fn new(width: i32, height: i32) -> RenderState {
        // This needs to be done once per WebGL context.
        let mut gpu_state = GpuState::new();
        let mut final_surface = gpu_state.create_target_surface(width, height);
        let drawing_surface = final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();

        RenderState {
            gpu_state,
            final_surface,
            drawing_surface,
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        let surface = self.gpu_state.create_target_surface(width, height);
        self.final_surface = surface;
        self.drawing_surface = self
            .final_surface
            .new_surface_with_dimensions((width, height))
            .unwrap();
    }

    pub fn flush(&mut self) {
        self.gpu_state
            .context
            .flush_and_submit_surface(&mut self.final_surface, None)
    }

    pub fn translate(&mut self, dx: f32, dy: f32) {
        self.drawing_surface.canvas().translate((dx, dy));
    }

    pub fn scale(&mut self, sx: f32, sy: f32) {
        self.drawing_surface.canvas().scale((sx, sy));
    }

    pub fn reset_canvas(&mut self) {
        self.drawing_surface
            .canvas()
            .clear(skia_safe::Color::TRANSPARENT);
        self.drawing_surface.canvas().reset_matrix();
    }
}
