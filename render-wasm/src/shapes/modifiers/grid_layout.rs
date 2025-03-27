#![allow(dead_code, unused_variables)]
use crate::math::{Bounds, Matrix, Point, Vector, VectorExt};
use crate::shapes::{GridData, LayoutData, Modifier, Shape};
use crate::uuid::Uuid;
use std::collections::{HashMap, VecDeque};

use super::common::GetBounds;

const MIN_SIZE: f32 = 0.01;
const MAX_SIZE: f32 = f32::INFINITY;

struct CellData<'a> {
    shape: &'a Shape,
    main_size: f32,
    across_size: f32,
}

fn calculate_cell_data<'a>(
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    shapes: &'a HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> Vec<CellData<'a>> {
    todo!()
}

fn child_position(child_bounds: &Bounds, cell: &CellData) -> Point {
    todo!()
}

pub fn reflow_grid_layout<'a>(
    shape: &Shape,
    layout_data: &LayoutData,
    grid_data: &GridData,
    shapes: &'a HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    let mut result = VecDeque::new();

    let cells = calculate_cell_data(shape, layout_data, grid_data, shapes, bounds);

    for cell in cells.iter() {
        let child = cell.shape;
        let child_bounds = bounds.find(child);
        let position = child_position(&child_bounds, cell);

        let mut transform = Matrix::default();
        let delta_v = Vector::new_points(&child_bounds.nw, &position);

        if delta_v.x.abs() > MIN_SIZE || delta_v.y.abs() > MIN_SIZE {
            transform.post_concat(&Matrix::translate(delta_v));
        }

        result.push_back(Modifier::transform(child.id, transform));
    }

    result
}
