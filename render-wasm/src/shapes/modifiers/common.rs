use std::collections::HashMap;

use crate::math::Bounds;
use crate::shapes::Shape;
use crate::uuid::Uuid;

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
