use std::collections::HashSet;

use crate::uuid::Uuid;

#[derive(Clone)]
pub struct FocusMode {
    shapes: HashSet<Uuid>,
    active: bool,
}

impl FocusMode {
    pub fn new() -> Self {
        FocusMode {
            shapes: HashSet::new(),
            active: false,
        }
    }

    pub fn clear(&mut self) {
        self.shapes.clear();
        self.active = false;
    }

    pub fn set_shapes(&mut self, shapes: Vec<Uuid>) {
        self.shapes = shapes.into_iter().collect();
    }

    /// Returns `true` if the given shape ID should be focused.
    /// If the `shapes` list is empty, focus applies to all shapes.
    pub fn should_focus(&self, id: &Uuid) -> bool {
        self.shapes.is_empty() || self.shapes.contains(id)
    }

    pub fn enter(&mut self, id: &Uuid) {
        if !self.active && self.should_focus(id) {
            self.active = true;
        }
    }

    pub fn exit(&mut self, id: &Uuid) {
        if self.active && self.should_focus(id) {
            self.active = false;
        }
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn reset(&mut self) {
        self.active = false;
    }
}
