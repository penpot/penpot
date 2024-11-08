use skia_safe as skia;
use std::collections::HashMap;
use std::vec::Vec;
use uuid::Uuid;

use crate::render::RenderState;
use crate::shapes::Shape;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions. Note that rust-skia data
/// structures are not thread safe, so a state must not be shared between different Web Workers.
pub(crate) struct State {
    pub render_state: RenderState,
    pub shapes: HashMap<Uuid, Shape>,
    pub display_list: Vec<Uuid>,
}

impl State {
  pub fn with_capacity(width: i32, height: i32, capacity: usize) -> Self {
    State {
      render_state: RenderState::new(width, height),
      shapes: HashMap::with_capacity(capacity),
      display_list: Vec::with_capacity(capacity)
    }
  }

  pub fn set_surface(&mut self, surface: skia::Surface) {
      self.render_state.surface = surface;
  }
}

/*
impl Iterator for State {
  type Item = Shape;

  pub fn next(&mut self) -> Option<Self::Item> {
    self.display_list.iter().next()
  }
}
*/
