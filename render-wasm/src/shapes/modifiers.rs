use std::collections::{HashMap, HashSet, VecDeque};

mod constraints;
mod flex_layout;

pub mod common;
pub mod grid_layout;

use crate::math::{self as math, bools, identitish, is_close_to, Bounds, Matrix, Point};
use common::GetBounds;

use crate::shapes::{
    ConstraintH, ConstraintV, Frame, Group, GrowType, Layout, Modifier, Shape, TransformEntry,
    TransformEntrySource, Type,
};
use crate::state::{ShapesPoolRef, State};
use crate::uuid::Uuid;

#[allow(clippy::too_many_arguments)]
fn propagate_children(
    shape: &Shape,
    shapes: ShapesPoolRef,
    parent_bounds_before: &Bounds,
    parent_bounds_after: &Bounds,
    transform: Matrix,
    bounds: &HashMap<Uuid, Bounds>,
) -> VecDeque<Modifier> {
    if identitish(&transform) {
        return VecDeque::new();
    }

    let mut result = VecDeque::new();

    for child_id in shape.children_ids_iter(true) {
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
            parent_bounds_before,
            parent_bounds_after,
            &child_bounds,
            constraint_h,
            constraint_v,
            transform,
            child.ignore_constraints,
        );

        result.push_back(Modifier::transform_propagate(*child_id, transform));
    }

    result
}

fn calculate_group_bounds(
    shape: &Shape,
    shapes: ShapesPoolRef,
    bounds: &HashMap<Uuid, Bounds>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(shape);
    let mut result = Vec::<Point>::new();

    for child_id in shape.children_ids_iter(true) {
        let Some(child) = shapes.get(child_id) else {
            continue;
        };

        let child_bounds = bounds.find(child);
        result.append(&mut child_bounds.points());
    }
    shape_bounds.with_points(result)
}

fn calculate_bool_bounds(
    shape: &Shape,
    shapes: ShapesPoolRef,
    bounds: &HashMap<Uuid, Bounds>,
    modifiers: &HashMap<Uuid, Matrix>,
) -> Option<Bounds> {
    let shape_bounds = bounds.find(shape);
    let children_ids = shape.children_ids(true);

    let Type::Bool(bool_data) = &shape.shape_type else {
        return Some(shape_bounds);
    };

    let mut subtree = shapes.subtree(&shape.id);
    subtree.set_modifiers(modifiers.clone());

    let path = bools::bool_from_shapes(bool_data.bool_type, &children_ids, &subtree);
    let result = path.bounds();

    Some(result)
}

fn set_pixel_precision(transform: &mut Matrix, bounds: &mut Bounds) {
    let tr = bounds.transform_matrix().unwrap_or_default();
    let tr_inv = tr.invert().unwrap_or_default();

    let x = bounds.min_x().round();
    let y = bounds.min_y().round();

    let width = bounds.width();
    let height = bounds.height();

    let scale_width = if width > 0.1 {
        f32::max(0.01, bounds.width().round() / bounds.width())
    } else {
        1.0
    };
    let scale_height = if height > 0.1 {
        f32::max(0.01, bounds.height().round() / bounds.height())
    } else {
        1.0
    };

    if f32::is_finite(scale_width)
        && f32::is_finite(scale_height)
        && (!math::is_close_to(scale_width, 1.0) || !math::is_close_to(scale_height, 1.0))
    {
        let mut round_transform = Matrix::scale((scale_width, scale_height));
        round_transform.post_concat(&tr);
        round_transform.pre_concat(&tr_inv);
        transform.post_concat(&round_transform);
        bounds.transform_mut(&round_transform);
    }

    let dx = x - bounds.min_x();
    let dy = y - bounds.min_y();

    if f32::is_finite(dx) && f32::is_finite(dy) {
        let round_transform = Matrix::translate((dx, dy));
        transform.post_concat(&round_transform);
        bounds.transform_mut(&round_transform);
    }
}

fn propagate_transform(
    entry: TransformEntry,
    pixel_precision: bool,
    state: &State,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
    modifiers: &mut HashMap<Uuid, Matrix>,
) {
    let Some(shape) = state.shapes.get(&entry.id) else {
        return;
    };

    let shapes = &state.shapes;
    let shape_bounds_before = bounds.find(shape);
    let mut shape_bounds_after = shape_bounds_before.transform(&entry.transform);

    let mut transform = entry.transform;

    // Only check the text layout when the width/height changes
    if !is_close_to(shape_bounds_before.width(), shape_bounds_after.width())
        || !is_close_to(shape_bounds_before.height(), shape_bounds_after.height())
    {
        if let Type::Text(text_content) = &mut shape.shape_type.clone() {
            match text_content.grow_type() {
                GrowType::AutoHeight => {
                    if text_content.needs_update_layout() {
                        text_content.update_layout(shape.selrect);
                    }
                    let height = text_content.size.height;
                    let resize_transform = math::resize_matrix(
                        &shape_bounds_after,
                        &shape_bounds_after,
                        shape_bounds_after.width(),
                        height,
                    );
                    shape_bounds_after = shape_bounds_after.transform(&resize_transform);
                    transform.post_concat(&resize_transform);
                }
                GrowType::AutoWidth => {
                    if text_content.needs_update_layout() {
                        text_content.update_layout(shape.selrect);
                    }
                    let width = text_content.width();
                    let height = text_content.size.height;
                    let resize_transform = math::resize_matrix(
                        &shape_bounds_after,
                        &shape_bounds_after,
                        width,
                        height,
                    );
                    shape_bounds_after = shape_bounds_after.transform(&resize_transform);
                    transform.post_concat(&resize_transform);
                }
                GrowType::Fixed => {}
            }
        }
    }

    if pixel_precision {
        set_pixel_precision(&mut transform, &mut shape_bounds_after);
    }

    if entry.propagate {
        let mut children = propagate_children(
            shape,
            shapes,
            &shape_bounds_before,
            &shape_bounds_after,
            transform,
            bounds,
        );
        entries.append(&mut children);
    }

    bounds.insert(shape.id, shape_bounds_after);

    let mut shape_modif = modifiers.get(&shape.id).copied().unwrap_or_default();
    shape_modif.post_concat(&transform);
    modifiers.insert(shape.id, shape_modif);

    let is_resize = !math::is_move_only_matrix(&transform);
    let is_propagate = entry.source == TransformEntrySource::Propagate;

    // If this is a layout and we're only moving don't need to reflow
    if shape.has_layout() && is_resize {
        entries.push_back(Modifier::reflow(shape.id));
    }

    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
        // When the parent is either a group or a layout we only mark for reflow
        // if the current transformation is not a move propagation.
        // If it's a move propagation we don't need to reflow, the parent is already changed.
        if (parent.has_layout() || parent.is_group_like()) && (is_resize || !is_propagate) {
            entries.push_back(Modifier::reflow(parent.id));
        }
    }
}

fn propagate_reflow(
    id: &Uuid,
    state: &State,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
    layout_reflows: &mut Vec<Uuid>,
    reflown: &mut HashSet<Uuid>,
    modifiers: &HashMap<Uuid, Matrix>,
) {
    let Some(shape) = state.shapes.get(id) else {
        return;
    };

    let shapes = &state.shapes;
    let mut reflow_parent = false;

    if reflown.contains(id) {
        return;
    }

    match &shape.shape_type {
        Type::Frame(Frame {
            layout: Some(_), ..
        }) => {
            let mut skip_reflow = false;
            if shape.is_layout_horizontal_fill() || shape.is_layout_vertical_fill() {
                if let Some(parent_id) = shape.parent_id {
                    if !reflown.contains(&parent_id) {
                        // If this is a fill layout but the parent has not been reflown yet
                        // we wait for the next iteration for reflow
                        skip_reflow = true;
                        reflow_parent = true;
                    }
                }
            }

            if shape.is_layout_vertical_auto() || shape.is_layout_horizontal_auto() {
                reflow_parent = true;
            }

            if !skip_reflow {
                layout_reflows.push(*id);
            }
        }
        Type::Group(Group { masked: true }) => {
            let children_ids = shape.children_ids(true);
            if let Some(child) = shapes.get(&children_ids[0]) {
                let child_bounds = bounds.find(child);
                bounds.insert(shape.id, child_bounds);
                reflow_parent = true;
            }
            reflown.insert(*id);
        }
        Type::Group(_) => {
            if let Some(shape_bounds) = calculate_group_bounds(shape, shapes, bounds) {
                bounds.insert(shape.id, shape_bounds);
                reflow_parent = true;
            }
            reflown.insert(*id);
        }
        Type::Bool(_) => {
            if let Some(shape_bounds) = calculate_bool_bounds(shape, shapes, bounds, modifiers) {
                bounds.insert(shape.id, shape_bounds);
                reflow_parent = true;
            }
            reflown.insert(*id);
        }
        _ => {
            // Other shapes don't have to be reflown
            reflow_parent = true;
        }
    }

    if let Some(parent) = shape.parent_id.and_then(|id| shapes.get(&id)) {
        if reflow_parent && (parent.has_layout() || parent.is_group_like()) {
            entries.push_back(Modifier::reflow(parent.id));
        }
    }
}

fn reflow_shape(
    id: &Uuid,
    state: &State,
    reflown: &mut HashSet<Uuid>,
    entries: &mut VecDeque<Modifier>,
    bounds: &mut HashMap<Uuid, Bounds>,
) {
    let Some(shape) = state.shapes.get(id) else {
        return;
    };

    let shapes = &state.shapes;

    let Type::Frame(frame_data) = &shape.shape_type else {
        return;
    };

    if let Some(Layout::FlexLayout(layout_data, flex_data)) = &frame_data.layout {
        let mut children =
            flex_layout::reflow_flex_layout(shape, layout_data, flex_data, shapes, bounds);
        entries.append(&mut children);
    } else if let Some(Layout::GridLayout(layout_data, grid_data)) = &frame_data.layout {
        let mut children =
            grid_layout::reflow_grid_layout(shape, layout_data, grid_data, shapes, bounds);
        entries.append(&mut children);
    }
    reflown.insert(*id);
}

pub fn propagate_modifiers(
    state: &State,
    modifiers: &[TransformEntry],
    pixel_precision: bool,
) -> Vec<TransformEntry> {
    let mut entries: VecDeque<_> = modifiers
        .iter()
        .map(|entry| {
            // If we receibe a identity matrix we force a reflow
            if math::identitish(&entry.transform) {
                Modifier::Reflow(entry.id)
            } else {
                Modifier::Transform(entry.clone())
            }
        })
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
                Modifier::Transform(entry) => propagate_transform(
                    entry,
                    pixel_precision,
                    state,
                    &mut entries,
                    &mut bounds,
                    &mut modifiers,
                ),
                Modifier::Reflow(id) => propagate_reflow(
                    &id,
                    state,
                    &mut entries,
                    &mut bounds,
                    &mut layout_reflows,
                    &mut reflown,
                    &modifiers,
                ),
            }
        }

        for id in layout_reflows.iter() {
            if reflown.contains(id) {
                continue;
            }
            reflow_shape(id, state, &mut reflown, &mut entries, &mut bounds);
        }
        layout_reflows = Vec::new();
    }

    modifiers
        .iter()
        .map(|(key, val)| TransformEntry::from_input(*key, *val))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::math::{Matrix, Point};
    use crate::shapes::*;
    use crate::state::ShapesPool;

    #[test]
    fn test_propagate_shape() {
        let parent_id = Uuid::new_v4();

        let shapes = {
            let mut shapes = ShapesPool::new();
            shapes.initialize(10);

            let child_id = Uuid::new_v4();
            let child = shapes.add_shape(child_id);
            child.set_selrect(3.0, 3.0, 2.0, 2.0);

            let parent = shapes.add_shape(parent_id);
            parent.set_shape_type(Type::Group(Group::default()));
            parent.add_child(child_id);
            parent.set_selrect(1.0, 1.0, 5.0, 5.0);

            shapes
        };

        let parent = shapes.get(&parent_id).unwrap();
        let mut transform = Matrix::scale((2.0, 1.5));
        let x = parent.selrect.x();
        let y = parent.selrect.y();
        transform.post_translate(Point::new(x, y));
        transform.pre_translate(Point::new(-x, -y));

        let bounds_before = parent.bounds();
        let bounds_after = bounds_before.transform(&transform);

        let result = propagate_children(
            parent,
            &shapes,
            &bounds_before,
            &bounds_after,
            transform,
            &HashMap::new(),
        );

        assert_eq!(result.len(), 1);
    }

    #[test]
    fn test_group_bounds() {
        let parent_id = Uuid::new_v4();
        let shapes = {
            let mut shapes = ShapesPool::new();
            shapes.initialize(10);

            let child1_id = Uuid::new_v4();
            let child1 = shapes.add_shape(child1_id);
            child1.set_selrect(3.0, 3.0, 2.0, 2.0);

            let child2_id = Uuid::new_v4();
            let child2 = shapes.add_shape(child2_id);
            child2.set_selrect(0.0, 0.0, 1.0, 1.0);

            let parent = shapes.add_shape(parent_id);
            parent.set_shape_type(Type::Group(Group::default()));
            parent.add_child(child1_id);
            parent.add_child(child2_id);
            parent.set_selrect(0.0, 0.0, 3.0, 3.0);
            shapes
        };

        let parent = shapes.get(&parent_id).unwrap();

        let bounds = calculate_group_bounds(parent, &shapes, &HashMap::new()).unwrap();

        assert_eq!(bounds.width(), 3.0);
        assert_eq!(bounds.height(), 3.0);
    }
}
