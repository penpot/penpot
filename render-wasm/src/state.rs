use std::collections::HashMap;

use skia_safe as skia;
use uuid::Uuid;

use crate::render::RenderState;
use crate::shapes::Shape;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub current_shape: Option<&'a mut Shape>,
    pub shapes: HashMap<Uuid, Shape>,
    pub modifiers: HashMap<Uuid, skia::Matrix>,
}

impl<'a> State<'a> {
    pub fn new(width: i32, height: i32, capacity: usize) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
            modifiers: HashMap::new(),
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        self.render_state.resize(width, height);
    }

    pub fn render_state(&'a mut self) -> &'a mut RenderState {
        &mut self.render_state
    }

    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(&mut self.shapes, &self.modifiers, timestamp)?;
        Ok(())
    }

    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .process_animation_frame(&mut self.shapes, &self.modifiers, timestamp)?;
        Ok(())
    }

    pub fn render_from_cache(&mut self) {
        let _ = self.render_state.render_from_cache();
    }

    pub fn use_shape(&'a mut self, id: Uuid) {
        if !self.shapes.contains_key(&id) {
            let new_shape = Shape::new(id);
            self.shapes.insert(id, new_shape);
        }
        self.current_id = Some(id);
        self.current_shape = self.shapes.get_mut(&id);
    }

    pub fn current_shape(&mut self) -> Option<&mut Shape> {
        self.current_shape.as_deref_mut()
    }

    pub fn set_background_color(&mut self, color: skia::Color) {
        self.render_state.set_background_color(color);
    }
}
