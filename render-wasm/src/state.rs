use std::collections::HashMap;
use uuid::Uuid;

use crate::render::RenderState;
use crate::shapes::Shape;
use crate::view::View;

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
    pub view: View,
}

impl<'a> State<'a> {
    pub fn with_capacity(width: i32, height: i32, capacity: usize) -> Self {
        State {
            render_state: RenderState::new(width, height),
            current_id: None,
            current_shape: None,
            shapes: HashMap::with_capacity(capacity),
            view: View {
                x: 0.,
                y: 0.,
                zoom: 1.,
                width: 0.,
                height: 0.,
            },
        }
    }

    pub fn render_state(&'a mut self) -> &'a mut RenderState {
        &mut self.render_state
    }

    pub fn navigate(&mut self) {
        self.render_state.navigate(&self.view, &self.shapes);
    }

    pub fn draw_all_shapes(&mut self) {
        self.render_state.draw_all_shapes(&self.view, &self.shapes);
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

    pub fn set_view(&mut self, zoom: f32, pan: (f32, f32), size: (f32, f32)) {
        let (x, y) = pan;
        self.view.x = x;
        self.view.y = y;

        self.view.zoom = zoom;

        let (w, h) = size;
        if self.view.width != w || self.view.height != h {
            self.view.width = w;
            self.view.height = h;

            self.render_state.resize(w as i32, h as i32);
        }
    }
}
