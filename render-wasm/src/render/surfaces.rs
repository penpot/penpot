use super::gpu_state::GpuState;
use skia_safe as skia;

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    pub target: skia::Surface,
    // keeps the current render
    pub current: skia::Surface,
    // keeps the current shape
    pub shape: skia::Surface,
    // used for rendering shadows
    pub shadow: skia::Surface,
    // for drawing the things that are over shadows.
    pub overlay: skia::Surface,
    // for drawing debug info.
    pub debug: skia::Surface,
}

impl Surfaces {
    pub fn new(gpu_state: &mut GpuState, (width, height): (i32, i32)) -> Self {
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
        }
    }

    pub fn set(&mut self, new_surface: skia::Surface) {
        let dim = (new_surface.width(), new_surface.height());
        self.target = new_surface;
        self.current = self.target.new_surface_with_dimensions(dim).unwrap();
        self.overlay = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shadow = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shape = self.target.new_surface_with_dimensions(dim).unwrap();
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
    }

    pub fn resize(&mut self, gpu_state: &mut GpuState, new_width: i32, new_height: i32) {
        self.set(gpu_state.create_target_surface(new_width, new_height));
    }
}
