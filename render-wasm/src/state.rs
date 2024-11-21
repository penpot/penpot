use std::collections::HashMap;
use uuid::Uuid;

use crate::math;
use crate::render::RenderState;
use crate::shapes::Shape;
use crate::view::Viewbox;

/// This struct holds the state of the Rust application between JS calls.
///
/// It is created by [init] and passed to the other exported functions.
/// Note that rust-skia data structures are not thread safe, so a state
/// must not be shared between different Web Workers.
pub(crate) struct State<'a> {
    pub debug: u32,
    pub render_state: RenderState,
    pub current_id: Option<Uuid>,
    pub current_shape: Option<&'a mut Shape>,
    pub shapes: HashMap<Uuid, Shape>,
    pub viewbox: Viewbox,
}

impl<'a> State<'a> {
    pub fn with_capacity(width: i32, height: i32, debug: u32, capacity: usize) -> Self {
        State {
            debug,
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
            viewbox: Viewbox {
                x: 0.,
                y: 0.,
                zoom: 1.,
                width: width as f32,
                height: height as f32,
                area: math::Rect::new_empty(),
            },
        }
    }

    pub fn resize(&mut self, width: i32, height: i32) {
        self.render_state.resize(width, height);
        self.viewbox.set_wh(width as f32, height as f32);
    }

    pub fn render_state(&'a mut self) -> &'a mut RenderState {
        &mut self.render_state
    }

    pub fn navigate(&mut self) {
        self.render_state
            .navigate(&self.viewbox, &self.shapes, self.debug);
    }

    pub fn render_all(&mut self) {
        self.render_state
            .render_all(&self.viewbox, &self.shapes, self.debug);
    }

    pub fn use_shape(&'a mut self, id: Uuid) {
        if !self.shapes.contains_key(&id) {
            let new_shape = Shape::new(id);
            self.shapes.insert(id, new_shape);
        }

        self.current_id = Some(id);
        self.current_shape = self.shapes.get_mut(&id);
    }

    pub fn current_shape(&'a mut self) -> Option<&'a mut Shape> {
        self.current_shape.as_deref_mut()
    }
}
