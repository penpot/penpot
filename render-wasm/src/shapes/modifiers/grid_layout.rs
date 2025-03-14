use crate::math::Bounds;
use crate::shapes::{GridData, LayoutData, Modifier, Shape};
use std::collections::{HashMap, VecDeque};
use uuid::Uuid;

pub fn reflow_grid_layout(
    _shape: &Shape,
    _layout_data: &LayoutData,
    _grid_data: &GridData,
    _shapes: &HashMap<Uuid, Shape>,
    _bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    // TODO
    VecDeque::new()
}
