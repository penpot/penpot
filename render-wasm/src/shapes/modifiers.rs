use std::collections::HashMap;

use skia::Matrix;
use skia_safe as skia;

use std::collections::HashSet;
use uuid::Uuid;

use crate::math::Bounds;
use crate::shapes::{ConstraintH, ConstraintV, Shape, TransformEntry};
use crate::state::State;

fn calculate_resize(
    constraint_h: ConstraintH,
    constraint_v: ConstraintV,
    parent_before: &Bounds,
    parent_after: &Bounds,
    child_before: &Bounds,
    child_after: &Bounds,
) -> Option<(f32, f32)> {
    let scale_width = match constraint_h {
        ConstraintH::Left | ConstraintH::Right | ConstraintH::Center => {
            parent_before.width() / parent_after.width()
        }
        ConstraintH::LeftRight => {
            let left = parent_before.left(child_before.nw);
            let right = parent_before.right(child_before.ne);
            let target_width = parent_after.width() - left - right;
            target_width / child_after.width()
        }
        _ => 1.0,
    };

    let scale_height = match constraint_v {
        ConstraintV::Top | ConstraintV::Bottom | ConstraintV::Center => {
            parent_before.height() / parent_after.height()
        }
        ConstraintV::TopBottom => {
            let top = parent_before.top(child_before.nw);
            let bottom = parent_before.bottom(child_before.sw);
            let target_height = parent_after.height() - top - bottom;
            target_height / child_after.height()
        }
        _ => 1.0,
    };

    if (scale_width - 1.0).abs() < f32::EPSILON && (scale_height - 1.0).abs() < f32::EPSILON {
        None
    } else {
        Some((scale_width, scale_height))
    }
}

fn calculate_displacement(
    constraint_h: ConstraintH,
    constraint_v: ConstraintV,
    parent_before: &Bounds,
    parent_after: &Bounds,
    child_before: &Bounds,
    child_after: &Bounds,
) -> Option<(f32, f32)> {
    let delta_x = match constraint_h {
        ConstraintH::Left | ConstraintH::LeftRight => {
            let target_left = parent_before.left(child_before.nw);
            let current_left = parent_after.left(child_after.nw);
            target_left - current_left
        }
        ConstraintH::Right => {
            let target_right = parent_before.right(child_before.ne);
            let current_right = parent_after.right(child_after.ne);
            current_right - target_right
        }
        ConstraintH::Center => {
            let delta_width = parent_after.width() - parent_before.width();
            let target_left = parent_before.left(child_before.nw);
            let current_left = parent_after.left(child_after.nw);
            target_left - current_left + delta_width / 2.0
        }
        _ => 0.0,
    };

    let delta_y = match constraint_v {
        ConstraintV::Top | ConstraintV::TopBottom => {
            let target_top = parent_before.top(child_before.nw);
            let current_top = parent_after.top(child_after.nw);
            target_top - current_top
        }
        ConstraintV::Bottom => {
            let target_bottom = parent_before.bottom(child_before.ne);
            let current_bottom = parent_after.bottom(child_after.ne);
            current_bottom - target_bottom
        }
        ConstraintV::Center => {
            let delta_height = parent_after.height() - parent_before.height();
            let target_top = parent_before.top(child_before.nw);
            let current_top = parent_after.top(child_after.nw);
            target_top - current_top + delta_height / 2.0
        }
        _ => 0.0,
    };

    if delta_x.abs() < f32::EPSILON && delta_y.abs() < f32::EPSILON {
        None
    } else {
        Some((delta_x, delta_y))
    }
}

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
        if let Some(child) = shapes.get(child_id) {
            let constraint_h = child.constraint_h(if shape.is_frame() {
                ConstraintH::Left
            } else {
                ConstraintH::Scale
            });

            let constraint_v = child.constraint_v(if shape.is_frame() {
                ConstraintV::Top
            } else {
                ConstraintV::Scale
            });
            // if the constrains are scale & scale or the transform has only moves we
            // can propagate as is
            if (constraint_h == ConstraintH::Scale && constraint_v == ConstraintV::Scale)
                || transform.is_translate()
            {
                result.push(TransformEntry::new(child_id.clone(), transform));
                continue;
            }

            if let Some(child_bounds_before) = parent_bounds_before.box_bounds(&child.bounds()) {
                let mut transform = transform;
                let mut child_bounds_after = child_bounds_before.transform(&transform);

                // Scale shape
                if let Some((scale_width, scale_height)) = calculate_resize(
                    constraint_h,
                    constraint_v,
                    &parent_bounds_before,
                    &parent_bounds_after,
                    &child_bounds_before,
                    &child_bounds_after,
                ) {
                    let center = child.center();

                    let mut parent_transform = parent_bounds_after
                        .transform_matrix()
                        .unwrap_or(Matrix::default());
                    parent_transform.post_translate(center);
                    parent_transform.pre_translate(-center);

                    let parent_transform_inv = &parent_transform.invert().unwrap();
                    let origin = parent_transform_inv.map_point(child_bounds_after.nw);

                    let mut scale = Matrix::scale((scale_width, scale_height));
                    scale.post_translate(origin);
                    scale.post_concat(&parent_transform);
                    scale.pre_translate(-origin);
                    scale.pre_concat(&parent_transform_inv);

                    child_bounds_after.transform_mut(&scale);
                    transform.post_concat(&scale);
                }

                // Translate position
                if let Some((delta_x, delta_y)) = calculate_displacement(
                    constraint_h,
                    constraint_v,
                    &parent_bounds_before,
                    &parent_bounds_after,
                    &child_bounds_before,
                    &child_bounds_after,
                ) {
                    let th = parent_bounds_after.hv(delta_x);
                    let tv = parent_bounds_after.vv(delta_y);
                    transform.post_concat(&Matrix::translate(th + tv));
                }

                result.push(TransformEntry::new(child_id.clone(), transform));
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
        if !processed.contains(&entry.id) {
            if let Some(shape) = state.shapes.get(&entry.id) {
                let mut children = propagate_shape(&state.shapes, shape, entry.transform);
                entries.append(&mut children);
                processed.insert(entry.id);
                result.push(entry.clone());
            }
        }
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::shapes::Type;
    use skia::Point;

    #[test]
    fn test_propagate_shape() {
        let mut shapes = HashMap::<Uuid, Shape>::new();

        let child_id = Uuid::new_v4();
        let mut child = Shape::new(child_id);
        child.set_selrect(3.0, 3.0, 2.0, 2.0);
        shapes.insert(child_id, child);

        let parent_id = Uuid::new_v4();
        let mut parent = Shape::new(parent_id);
        parent.set_shape_type(Type::Group);
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
