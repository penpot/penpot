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

    pub fn reset(&mut self, color: skia::Color) {
        self.target.canvas().clear(color);
        self.shape.canvas().restore_to_count(1);
        self.current.canvas().restore_to_count(1);
        self.shape
            .canvas()
            .clear(color)
            .reset_matrix();
        self.current
            .canvas()
            .clear(color)
            .reset_matrix();
        self.shadow
            .canvas()
            .clear(color)
            .reset_matrix();
        self.overlay
            .canvas()
            .clear(color)
            .reset_matrix();
        self.debug
            .canvas()
            .clear(skia::Color::TRANSPARENT)
            .reset_matrix();
    }
}

pub struct SurfaceRef {
    pub in_use: bool,
    pub surface: skia::Surface,
}

pub struct SurfacePool {
    pub surfaces: Vec<SurfaceRef>,
    pub index: usize,
}

impl SurfaceRef {
    pub fn allocated(&mut self) {
        self.in_use = true;
    }

    pub fn deallocated(&mut self) {
        self.in_use = false;
    }
}

impl SurfacePool {
    pub fn new(surface: &mut skia::Surface, dims: skia::ISize) -> Self {
        let mut surfaces = Vec::new();
        for _ in 0..32 {
            surfaces.push(surface.new_surface_with_dimensions(dims).unwrap())
        }

        SurfacePool {
            index: 0,
            surfaces: surfaces
                .into_iter()
                .map(|surface| SurfaceRef {
                    surface: surface,
                    in_use: false,
                })
                .collect(),
        }
    }

    pub fn allocate(&mut self) -> Result<skia::Surface, String> {
        let start = self.index;
        let len = self.surfaces.len();
        loop {
            self.index = (self.index + 1) % len;
            if self.index == start {
                return Err("Not enough surfaces in the pool".into());
            }
            if let Some(surface_ref) = self.surfaces.get(self.index) {
                return Ok(surface_ref.surface.clone());
            }
        }
    }
}
