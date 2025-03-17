use std::collections::{HashMap, HashSet, VecDeque};

mod common;
mod constraints;
mod flex_layout;
mod grid_layout;

use uuid::Uuid;

use common::GetBounds;

use crate::math::{Bounds, Matrix, Point};
use crate::shapes::{
    ConstraintH, ConstraintV, Frame, Group, Layout, Modifier, Shape, TransformEntry, Type,
};
use crate::state::State;

fn propagate_children(
    shape: &Shape,
    shapes: &HashMap<Uuid, Shape>,
    parent_bounds_before: &Bounds,
    parent_bounds_after: &Bounds,
    transform: Matrix,
    bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    if shape.children.len() == 0 {
        return VecDeque::new();
    }

    let mut result = VecDeque::new();

    for child_id in shape.children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);

        let constraint_h = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => ConstraintH::Left,
            Type::Frame(_) => child.constraint_h(ConstraintH::Left),
            _ => child.constraint_h(ConstraintH::Scale),
        };

        let constraint_v = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => ConstraintV::Top,
            Type::Frame(_) => child.constraint_v(ConstraintV::Top),
            _ => child.constraint_v(ConstraintV::Scale),
        };

        let transform = constraints::propagate_shape_constraints(
            &parent_bounds_before,
            &parent_bounds_after,
            &child_bounds,
            constraint_h,
            constraint_v,
            transform,
        );

        result.push_back(Modifier::transform(*child_id, transform));
    }

    result
}

fn calculate_group_bounds(
    shape: &Shape,
    shapes: &HashMap<Uuid, Shape>,
    bounds: &HashMap<Uuid, Bounds>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(&shape);
    let mut result = Vec::<Point>::new();
    for child_id in shape.children.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);
        result.append(&mut child_bounds.points());
    }

    shape_bounds.from_points(result)
}

pub fn propagate_modifiers(state: &State, modifiers: Vec<TransformEntry>) -> Vec<TransformEntry> {
    let shapes = &state.shapes;

    let mut entries: VecDeque<_> = modifiers
        .iter()
        .map(|entry| Modifier::Transform(entry.clone()))
        .collect();
    let mut modifiers = HashMap::<Uuid, Matrix>::new();
    let mut bounds = HashMap::<Uuid, Bounds>::new();

    let mut reflown = HashSet::<Uuid>::new();
    let mut layout_reflows = Vec::<Uuid>::new();

    // We first propagate the transforms to the children and then after
    // recalculate the layouts. The layout can create further transforms that
    // we need to re-propagate.
    // In order for loop to eventualy finish, we limit the flex reflow to just
    // one (the reflown set).
    while !entries.is_empty() {
        while let Some(modifier) = entries.pop_front() {
            match modifier {
                Modifier::Transform(entry) => {
                    // println!("Transform {}", entry.id);
                    let Some(shape) = state.shapes.get(&entry.id) else {
                        continue;
                    };

                    let shape_bounds_before = bounds.find(&shape);
                    let shape_bounds_after = shape_bounds_before.transform(&entry.transform);

                    if entry.propagate {
                        let mut children = propagate_children(
                            shape,
                            shapes,
                            &shape_bounds_before,
                            &shape_bounds_after,
                            entry.transform,
                            &bounds,
                        );

                        entries.append(&mut children);
                    }

                    bounds.insert(shape.id, shape_bounds_after);

                    let default_matrix = Matrix::default();
                    let mut shape_modif =
                        modifiers.get(&shape.id).unwrap_or(&default_matrix).clone();
                    shape_modif.post_concat(&entry.transform);
                    modifiers.insert(shape.id, shape_modif);

                    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
                        if parent.has_layout() || parent.is_group_like() {
                            entries.push_back(Modifier::reflow(parent.id));
                        }
                    }
                }

                Modifier::Reflow(id) => {
                    let Some(shape) = state.shapes.get(&id) else {
                        continue;
                    };

                    match &shape.shape_type {
                        Type::Frame(Frame {
                            layout: Some(_), ..
                        }) => {
                            if !reflown.contains(&id) {
                                layout_reflows.push(id);
                                reflown.insert(id);
                            }
                        }
                        Type::Group(Group { masked: true }) => {
                            if let Some(child) = shapes.get(&shape.children[0]) {
                                let child_bounds = bounds.find(&child);
                                bounds.insert(shape.id, child_bounds);
                            }
                        }
                        Type::Group(_) => {
                            if let Some(shape_bounds) =
                                calculate_group_bounds(shape, shapes, &bounds)
                            {
                                bounds.insert(shape.id, shape_bounds);
                            }
                        }
                        Type::Bool(_) => {
                            // TODO: How to calculate from rust the new box? we need to calculate the
                            // new path... impossible right now. I'm going to use for the moment the group
                            // calculation
                            if let Some(shape_bounds) =
                                calculate_group_bounds(shape, shapes, &bounds)
                            {
                                bounds.insert(shape.id, shape_bounds);
                            }
                        }
                        _ => {
                            // Other shapes don't have to be reflown
                        }
                    }

                    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
                        if parent.has_layout() || parent.is_group_like() {
                            entries.push_back(Modifier::reflow(parent.id));
                        }
                    }
                }
            }
        }

        for id in layout_reflows.iter() {
            let Some(shape) = state.shapes.get(&id) else {
                continue;
            };

            let Type::Frame(frame_data) = &shape.shape_type else {
                continue;
            };

            if let Some(Layout::FlexLayout(layout_data, flex_data)) = &frame_data.layout {
                let mut children =
                    flex_layout::reflow_flex_layout(shape, layout_data, flex_data, shapes, &bounds);
                entries.append(&mut children);
            }

            if let Some(Layout::GridLayout(layout_data, grid_data)) = &frame_data.layout {
                let mut children =
                    grid_layout::reflow_grid_layout(shape, layout_data, grid_data, shapes, &bounds);
                entries.append(&mut children);
            }
        }
        layout_reflows = Vec::new();
    }

    modifiers
        .iter()
        .map(|(key, val)| TransformEntry::new(*key, *val))
        .collect()
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

        let bounds_before = parent.bounds();
        let bounds_after = bounds_before.transform(&transform);

        let result = propagate_children(
            &parent,
            &shapes,
            &bounds_before,
            &bounds_after,
            transform,
            &HashMap::<Uuid, Bounds>::new(),
        );

        assert_eq!(result.len(), 1);
    }

    #[test]
    fn test_group_bounds() {
        let mut shapes = HashMap::<Uuid, Shape>::new();

        let child1_id = Uuid::new_v4();
        let mut child1 = Shape::new(child1_id);
        child1.set_selrect(3.0, 3.0, 2.0, 2.0);
        shapes.insert(child1_id, child1);

        let child2_id = Uuid::new_v4();
        let mut child2 = Shape::new(child2_id);
        child2.set_selrect(0.0, 0.0, 1.0, 1.0);
        shapes.insert(child2_id, child2);

        let parent_id = Uuid::new_v4();
        let mut parent = Shape::new(parent_id);
        parent.set_shape_type(Type::Group(Group::default()));
        parent.add_child(child1_id);
        parent.add_child(child2_id);
        parent.set_selrect(0.0, 0.0, 3.0, 3.0);
        shapes.insert(parent_id, parent.clone());

        let bounds =
            calculate_group_bounds(&parent, &shapes, HashMap::<Uuid, Bounds>::new()).unwrap();

        assert_eq!(bounds.width(), 3.0);
        assert_eq!(bounds.height(), 3.0);
    }
}
