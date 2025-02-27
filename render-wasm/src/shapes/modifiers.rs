use std::collections::HashMap;

mod constraints;
mod flex_layout;
mod grid_layout;

use std::collections::HashSet;
use uuid::Uuid;

use crate::math::Matrix;
use crate::shapes::{ConstraintH, ConstraintV, Frame, Layout, Shape, TransformEntry, Type};
use crate::state::State;

fn propagate_shape(
    shapes: &HashMap<Uuid, Shape>,
    shape: &Shape,
    transform: Matrix,
) -> Vec<TransformEntry> {
    if shape.children.len() == 0 {
        return vec![];
    }

    let parent_bounds_before = shape.bounds();
    let parent_bounds_after = parent_bounds_before.transform(&transform);
    let mut result = Vec::new();

    for child_id in shape.children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        match &shape.shape_type {
            Type::Frame(Frame { layout, .. }) => {
                println!("{layout:?}");
                match layout {
                    Some(Layout::FlexLayout(layout_data, flex_data)) => {
                        flex_layout::propagate_shape_constraints(
                            layout_data,
                            flex_data,
                            &parent_bounds_before,
                            &parent_bounds_after,
                            child,
                            transform,
                            &mut result,
                        )
                    }
                    Some(Layout::GridLayout(layout_data, grid_data)) => {
                        grid_layout::propagate_shape_constraints(
                            layout_data,
                            grid_data,
                            &parent_bounds_before,
                            &parent_bounds_after,
                            child,
                            transform,
                            &mut result,
                        )
                    }
                    _ => {
                        let constraint_h = child.constraint_h(ConstraintH::Left);
                        let constraint_v = child.constraint_v(ConstraintV::Top);
                        constraints::propagate_shape_constraints(
                            &parent_bounds_before,
                            &parent_bounds_after,
                            child,
                            constraint_h,
                            constraint_v,
                            transform,
                            &mut result,
                        )
                    }
                }},

            _ => {
                let constraint_h = child.constraint_h(ConstraintH::Scale);
                let constraint_v = child.constraint_v(ConstraintV::Scale);
                constraints::propagate_shape_constraints(
                    &parent_bounds_before,
                    &parent_bounds_after,
                    child,
                    constraint_h,
                    constraint_v,
                    transform,
                    &mut result,
                )
            }
        }
    }
    result
}

pub fn propagate_modifiers(state: &State, modifiers: Vec<TransformEntry>) -> Vec<TransformEntry> {
    let mut entries = modifiers.clone();
    let mut processed = HashSet::<Uuid>::new();
    let mut result = Vec::<TransformEntry>::new();

    // Propagate the transform to children
    while let Some(entry) = entries.pop() {
        if processed.contains(&entry.id) {
            continue;
        }

        let Some(shape) = state.shapes.get(&entry.id) else {
            continue;
        };

        let mut children = propagate_shape(&state.shapes, shape, entry.transform);
        entries.append(&mut children);
        processed.insert(entry.id);
        result.push(entry.clone());
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::math::{Matrix, Point};
    use crate::shapes::*;

    #[test]
    fn test_propagate_shape() {
        let mut shapes = HashMap::<Uuid, Shape>::new();

        let child_id = Uuid::new_v4();
        let mut child = Shape::new(child_id);
        child.set_selrect(3.0, 3.0, 2.0, 2.0);
        shapes.insert(child_id, child);

        let parent_id = Uuid::new_v4();
        let mut parent = Shape::new(parent_id);
        parent.set_shape_type(Type::Group(Group::default()));
        parent.add_child(child_id);
        parent.set_selrect(1.0, 1.0, 5.0, 5.0);
        shapes.insert(parent_id, parent.clone());

        let mut transform = Matrix::scale((2.0, 1.5));
        let x = parent.selrect.x();
        let y = parent.selrect.y();
        transform.post_translate(Point::new(x, y));
        transform.pre_translate(Point::new(-x, -y));

        let result = propagate_shape(&shapes, &parent, transform);

        assert_eq!(result.len(), 1);
    }
}
