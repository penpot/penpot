use super::gpu_state::GpuState;
use crate::shapes::Shape;
use skia_safe::{self as skia, Paint, RRect};

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum SurfaceId {
    Target,
    Current,
    Fills,
    Strokes,
    Shadow,
    DropShadows,
    Overlay,
    Debug,
}

pub struct Surfaces {
    // is the final destination surface, the one that it is represented in the canvas element.
    target: skia::Surface,
    // keeps the current render
    current: skia::Surface,
    // keeps the current shape's fills
    shape_fills: skia::Surface,
    // keeps the current shape's strokes
    shape_strokes: skia::Surface,
    // used for rendering shadows
    shadow: skia::Surface,
    // used for new shadow rendering
    drop_shadows: skia::Surface,
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
        let drop_shadows = target.new_surface_with_dimensions((width, height)).unwrap();
        let overlay = target.new_surface_with_dimensions((width, height)).unwrap();
        let shape_fills = target.new_surface_with_dimensions((width, height)).unwrap();
        let shape_strokes = target.new_surface_with_dimensions((width, height)).unwrap();
        let debug = target.new_surface_with_dimensions((width, height)).unwrap();

        Surfaces {
            target,
            current,
            shadow,
            drop_shadows,
            overlay,
            shape_fills,
            shape_strokes,
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

    pub fn apply_mut(&mut self, ids: &[SurfaceId], mut f: impl FnMut(&mut skia::Surface) -> ()) {
        for id in ids {
            let surface = self.get_mut(*id);
            f(surface);
        }
    }

    fn get_mut(&mut self, id: SurfaceId) -> &mut skia::Surface {
        match id {
            SurfaceId::Target => &mut self.target,
            SurfaceId::Current => &mut self.current,
            SurfaceId::Shadow => &mut self.shadow,
            SurfaceId::DropShadows => &mut self.drop_shadows,
            SurfaceId::Overlay => &mut self.overlay,
            SurfaceId::Fills => &mut self.shape_fills,
            SurfaceId::Strokes => &mut self.shape_strokes,
            SurfaceId::Debug => &mut self.debug,
        }
    }

    fn reset_from_target(&mut self, target: skia::Surface) {
        let dim = (target.width(), target.height());
        self.target = target;
        self.current = self.target.new_surface_with_dimensions(dim).unwrap();
        self.overlay = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shadow = self.target.new_surface_with_dimensions(dim).unwrap();
        self.drop_shadows = self.target.new_surface_with_dimensions(dim).unwrap();
        self.shape_fills = self.target.new_surface_with_dimensions(dim).unwrap();
        self.debug = self.target.new_surface_with_dimensions(dim).unwrap();
    }

    pub fn draw_rect_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        if let Some(corners) = shape.shape_type.corners() {
            let rrect = RRect::new_rect_radii(shape.selrect, &corners);
            self.canvas(id).draw_rrect(rrect, paint);
        } else {
            self.canvas(id).draw_rect(shape.selrect, paint);
        }
    }

    pub fn draw_circle_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        self.canvas(id).draw_oval(shape.selrect, paint);
    }

    pub fn draw_path_to(&mut self, id: SurfaceId, shape: &Shape, paint: &Paint) {
        if let Some(path) = shape.get_skia_path() {
            self.canvas(id).draw_path(&path, paint);
        }
    }
}
