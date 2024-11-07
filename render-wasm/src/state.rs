use skia_safe as skia;
use std::collections::HashMap;
use std::vec::Vec;
use uuid::Uuid;

use crate::render::GpuState;
use crate::shapes::Shape;

pub struct RenderState {
    pub gpu_state: GpuState,
    pub surface: skia::Surface,
}

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions. Note that rust-skia data
/// structures are not thread safe, so a state must not be shared between different Web Workers.
pub struct State {
  // pub render_state: RenderState,
    pub gpu_state: GpuState,
    pub surface: skia::Surface,

    pub shapes: HashMap<Uuid, Shape>,
    pub display_list: Vec<Uuid>,
}

impl State {
  pub fn new(gpu_state: GpuState, surface: skia::Surface) -> Self {
      State {
        gpu_state,
        surface,
        shapes: HashMap::with_capacity(2048),
        display_list: Vec::with_capacity(2048)
      }
  }

  pub fn with_capacity(gpu_state: GpuState, surface: skia::Surface, capacity: usize) -> Self {
    State {
      gpu_state,
      surface,
      shapes: HashMap::with_capacity(capacity),
      display_list: Vec::with_capacity(capacity)
    }
  }

  pub fn set_surface(&mut self, surface: skia::Surface) {
      self.surface = surface;
  }

  pub fn get_shape_by_id(&mut self, id: &Uuid) -> Option<&Shape> {
    self.shapes.get(id)
  }
}

impl Iterator for State {
  type Item = Shape;

  pub fn next(&mut self) -> Option<Self::Item> {
    self.display_list.iter().next()
  }
}
