use std::collections::HashMap;

use crate::math::Bounds;
use crate::shapes::Shape;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

pub trait GetBounds {
    fn find(&self, shape: &Shape) -> Bounds;
}

impl GetBounds for HashMap<Uuid, Bounds> {
    fn find(&self, shape: &Shape) -> Bounds {
        self.get(&shape.id).copied().unwrap_or(shape.bounds())
    }
}

/// Whether a flex layout has a child it places (see `Shape::is_in_flow`).
pub fn has_in_flow_children(shape: &Shape, shapes: ShapesPoolRef) -> bool {
    shape
        .children_ids_iter(true)
        .filter_map(|id| shapes.get(id))
        .any(Shape::is_in_flow)
}

/// Whether a layout has a child that is not absolute. Hidden children count,
/// as in the CLJS layout.
pub fn has_non_absolute_children(shape: &Shape, shapes: ShapesPoolRef) -> bool {
    shape
        .children_ids_iter(true)
        .filter_map(|id| shapes.get(id))
        .any(|child| !child.is_absolute())
}
