use std::collections::{HashMap, HashSet, VecDeque};
mod common;
mod constraints;
mod flex_layout;
mod grid_layout;

use common::GetBounds;

use crate::math::{identitish, Bounds, Matrix, Point};
use crate::shapes::{
    modified_children_ids, ConstraintH, ConstraintV, Frame, Group, Layout, Modifier, Shape,
    StructureEntry, TransformEntry, Type,
};
use crate::state::State;
use crate::uuid::Uuid;

fn propagate_children(
    shape: &Shape,
    shapes: &HashMap<Uuid, Shape>,
    parent_bounds_before: &Bounds,
    parent_bounds_after: &Bounds,
    transform: Matrix,
    bounds: &HashMap<Uuid, Bounds>,
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> VecDeque<Modifier> {
    let children_ids = modified_children_ids(shape, structure.get(&shape.id));

    if children_ids.len() == 0 || identitish(transform) {
        return VecDeque::new();
    }

    let mut result = VecDeque::new();

    for child_id in children_ids.iter() {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);

        let constraint_h = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => {
                if child.is_absolute() {
                    child.constraint_h(ConstraintH::Left)
                } else {
                    ConstraintH::Left
                }
            }
            Type::Frame(_) => child.constraint_h(ConstraintH::Left),
            _ => child.constraint_h(ConstraintH::Scale),
        };

        let constraint_v = match &shape.shape_type {
            Type::Frame(Frame {
                layout: Some(_), ..
            }) => {
                if child.is_absolute() {
                    child.constraint_v(ConstraintV::Top)
                } else {
                    ConstraintV::Top
                }
            }
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
    structure: &HashMap<Uuid, Vec<StructureEntry>>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(&shape);
    let mut result = Vec::<Point>::new();

    let children_ids = modified_children_ids(shape, structure.get(&shape.id));
    for child_id in children_ids.iter() {
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

    for (id, _) in &state.structure {
        if id != &Uuid::nil() {
            entries.push_back(Modifier::Reflow(*id));
        }
    }

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
                            &state.structure,
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

                    let mut reflow_parent = false;

                    match &shape.shape_type {
                        Type::Frame(Frame {
                            layout: Some(_), ..
                        }) => {
                            if !reflown.contains(&id) {
                                let mut skip_reflow = false;
                                if shape.is_layout_horizontal_fill()
                                    || shape.is_layout_vertical_fill()
                                {
                                    if let Some(parent_id) = shape.parent_id {
                                        if !reflown.contains(&parent_id) {
                                            // If this is a fill layout but the parent has not been reflown yet
                                            // we wait for the next iteration for reflow
                                            skip_reflow = true;
                                            reflow_parent = true;
                                        }
                                    }
                                }

                                if shape.is_layout_vertical_auto()
                                    || shape.is_layout_horizontal_auto()
                                {
                                    reflow_parent = true;
                                }

                                if !skip_reflow {
                                    layout_reflows.push(id);
                                }
                            }
                        }
                        Type::Group(Group { masked: true }) => {
                            let children_ids =
                                modified_children_ids(shape, state.structure.get(&shape.id));
                            if let Some(child) = shapes.get(&children_ids[0]) {
                                let child_bounds = bounds.find(&child);
                                bounds.insert(shape.id, child_bounds);
                                reflow_parent = true;
                            }
                        }
                        Type::Group(_) => {
                            if let Some(shape_bounds) =
                                calculate_group_bounds(shape, shapes, &bounds, &state.structure)
                            {
                                bounds.insert(shape.id, shape_bounds);
                                reflow_parent = true;
                            }
                        }
                        Type::Bool(_) => {
                            // TODO: How to calculate from rust the new box? we need to calculate the
                            // new path... impossible right now. I'm going to use for the moment the group
                            // calculation
                            if let Some(shape_bounds) =
                                calculate_group_bounds(shape, shapes, &bounds, &state.structure)
                            {
                                bounds.insert(shape.id, shape_bounds);
                                reflow_parent = true;
                            }
                        }
                        _ => {
                            // Other shapes don't have to be reflown
                        }
                    }

                    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
                        if reflow_parent && (parent.has_layout() || parent.is_group_like()) {
                            entries.push_back(Modifier::reflow(parent.id));
                        }
                    }
                }
            }
        }

        for id in layout_reflows.iter() {
            if reflown.contains(&id) {
                continue;
            }

            let Some(shape) = state.shapes.get(&id) else {
                continue;
            };

            let Type::Frame(frame_data) = &shape.shape_type else {
                continue;
            };

            if let Some(Layout::FlexLayout(layout_data, flex_data)) = &frame_data.layout {
                let mut children = flex_layout::reflow_flex_layout(
                    shape,
                    layout_data,
                    flex_data,
                    shapes,
                    &mut bounds,
                    &state.structure,
                );
                entries.append(&mut children);
            }

            if let Some(Layout::GridLayout(layout_data, grid_data)) = &frame_data.layout {
                let mut children = grid_layout::reflow_grid_layout(
                    shape,
                    layout_data,
                    grid_data,
                    shapes,
                    &mut bounds,
                    &state.structure,
                );
                entries.append(&mut children);
            }
            reflown.insert(*id);
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
            &HashMap::new(),
            &HashMap::new(),
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
            calculate_group_bounds(&parent, &shapes, &HashMap::new(), &HashMap::new()).unwrap();

        assert_eq!(bounds.width(), 3.0);
        assert_eq!(bounds.height(), 3.0);
    }
}
