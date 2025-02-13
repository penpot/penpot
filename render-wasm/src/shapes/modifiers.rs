use skia_safe as skia;

use std::collections::HashSet;
use uuid::Uuid;

use crate::shapes::{Shape, TransformEntry};
use crate::state::State;

fn propagate_shape(_state: &State, shape: &Shape, transform: skia::Matrix) -> Vec<TransformEntry> {
    let children: Vec<TransformEntry> = shape
        .children
        .iter()
        .map(|id| TransformEntry {
            id: id.clone(),
            transform,
        })
        .collect();

    children
}

pub fn propagate_modifiers(state: &State, modifiers: Vec<TransformEntry>) -> Vec<TransformEntry> {
    let mut entries = modifiers.clone();
    let mut processed = HashSet::<Uuid>::new();
    let mut result = Vec::<TransformEntry>::new();

    // Propagate the transform to children
    while let Some(entry) = entries.pop() {
        if !processed.contains(&entry.id) {
            if let Some(shape) = state.shapes.get(&entry.id) {
                let mut children = propagate_shape(state, shape, entry.transform);
                entries.append(&mut children);
                processed.insert(entry.id);
                result.push(entry.clone());
            }
        }
    }

    result
}
