use super::gpu_state::GpuState;
use skia_safe as skia;

pub enum SurfaceId {
    Target,
    Current,
    Shape,
    Shadow,
    Overlay,
    Debug,
}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    // keeps the current render
    current: skia::Surface,
    // keeps the current shape
    shape: skia::Surface,
    // used for rendering shadows
    shadow: skia::Surface,
    // for drawing the things that are over shadows.
    overlay: skia::Surface,
    // for drawing debug info.
    debug: skia::Surface,

    sampling_options: skia::SamplingOptions,
}

impl Surfaces {
    pub fn new(
        gpu_state: &mut GpuState,
        (width, height): (i32, i32),
        sampling_options: skia::SamplingOptions,
    ) -> Self {
        let mut target = gpu_state.create_target_surface(width, height);
        let current = target.new_surface_with_dimensions((width, height)).unwrap();
        let shadow = target.new_surface_with_dimensions((width, height)).unwrap();
        let overlay = target.new_surface_with_dimensions((width, height)).unwrap();
        let shape = target.new_surface_with_dimensions((width, height)).unwrap();
        let debug = target.new_surface_with_dimensions((width, height)).unwrap();

        Surfaces {
            target,
            current,
            shadow,
            overlay,
            shape,
            debug,
            sampling_options,
        }
    }

    pub fn resize(&mut self, gpu_state: &mut GpuState, new_width: i32, new_height: i32) {
        self.reset_from_target(gpu_state.create_target_surface(new_width, new_height));
    }

    pub fn snapshot(&mut self, id: SurfaceId) -> skia::Image {
        self.get_mut(id).image_snapshot()
    }

    pub fn canvas(&mut self, id: SurfaceId) -> &skia::Canvas {
        self.get_mut(id).canvas()
    }

    pub fn flush_and_submit(&mut self, gpu_state: &mut GpuState, id: SurfaceId) {
        let surface = self.get_mut(id);
        gpu_state.context.flush_and_submit_surface(surface, None);
    }

    pub fn draw_into(&mut self, from: SurfaceId, to: SurfaceId, paint: Option<&skia::Paint>) {
        let sampling_options = self.sampling_options;

        self.get_mut(from)
            .clone()
            .draw(self.canvas(to), (0.0, 0.0), sampling_options, paint);
    }

    fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Current => &mut self.current,
            SurfaceId::Shadow => &mut self.shadow,
            SurfaceId::Overlay => &mut self.overlay,
            SurfaceId::Shape => &mut self.shape,
            SurfaceId::Debug => &mut self.debug,
        }
    }

    fn reset_from_target(&mut self, target: skia::Surface) {
        let dim = (target.width(), target.height());
        self.target = target;
        self.current = self.target.new_surface_with_dimensions(dim).unwrap();
        self.overlay = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shadow = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shape = self.target.new_surface_with_dimensions(dim).unwrap();
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
    }
}
