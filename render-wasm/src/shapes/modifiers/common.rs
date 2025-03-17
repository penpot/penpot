use std::collections::HashMap;
use uuid::Uuid;

use crate::math::Bounds;
use crate::shapes::Shape;

pub trait GetBounds {
    fn find(&self, shape: &Shape) -> Bounds;
}

impl GetBounds for HashMap<Uuid, Bounds> {
    fn find(&self, shape: &Shape) -> Bounds {
        self.get(&shape.id)
            .map(|b| b.clone())
            .unwrap_or(shape.bounds())
    }
}
