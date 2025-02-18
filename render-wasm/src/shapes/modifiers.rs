use std::collections::HashMap;

use skia::Matrix;
use skia_safe as skia;

use std::collections::HashSet;
use uuid::Uuid;

use crate::math::Bounds;
use crate::shapes::{ConstraintH, ConstraintV, Shape, TransformEntry};
use crate::state::State;

fn calculate_new_bounds(
    constraint_h: ConstraintH,
    constraint_v: ConstraintV,
    parent_before: &Bounds,
    parent_after: &Bounds,
    child_bounds: &Bounds,
) -> (f32, f32, f32, f32) {
    let (delta_left, scale_width) = match constraint_h {
        ConstraintH::Scale => {
            let width_scale = parent_after.width() / parent_before.width();
            let target_left = parent_before.left(child_bounds.nw) * width_scale;
            let current_left = parent_after.left(child_bounds.nw);
            (target_left - current_left, width_scale)
        }
        ConstraintH::Left => {
            let target_left = parent_before.left(child_bounds.nw);
            let current_left = parent_after.left(child_bounds.nw);
            (target_left - current_left, 1.0)
        }
        ConstraintH::Right => {
            let target_right = parent_before.right(child_bounds.ne);
            let current_right = parent_after.right(child_bounds.ne);
            (current_right - target_right, 1.0)
        }
        ConstraintH::LeftRight => {
            let target_left = parent_before.left(child_bounds.nw);
            let target_right = parent_before.right(child_bounds.ne);
            let current_left = parent_after.left(child_bounds.nw);
            let new_width = parent_after.width() - target_left - target_right;
            let width_scale = new_width / child_bounds.width();
            (target_left - current_left, width_scale)
        }
        ConstraintH::Center => {
            let delta_width = parent_after.width() - parent_before.width();
            let delta_left = delta_width / 2.0;
            (delta_left, 1.0)
        }
    };

    let (delta_top, scale_height) = match constraint_v {
        ConstraintV::Scale => {
            let height_scale = parent_after.height() / parent_before.height();
            let target_top = parent_before.top(child_bounds.nw) * height_scale;
            let current_top = parent_after.top(child_bounds.nw);
            (target_top - current_top, height_scale)
        }
        ConstraintV::Top => {
            let height_scale = 1.0;
            let target_top = parent_before.top(child_bounds.nw);
            let current_top = parent_after.top(child_bounds.nw);
            (target_top - current_top, height_scale)
        }
        ConstraintV::Bottom => {
            let target_bottom = parent_before.bottom(child_bounds.sw);
            let current_bottom = parent_after.bottom(child_bounds.sw);
            (current_bottom - target_bottom, 1.0)
        }
        ConstraintV::TopBottom => {
            let target_top = parent_before.top(child_bounds.nw);
            let target_bottom = parent_before.bottom(child_bounds.sw);
            let current_top = parent_after.top(child_bounds.nw);
            let new_height = parent_after.height() - target_top - target_bottom;
            let height_scale = new_height / child_bounds.height();
            (target_top - current_top, height_scale)
        }
        ConstraintV::Center => {
            let delta_height = parent_after.height() - parent_before.height();
            let delta_top = delta_height / 2.0;
            (delta_top, 1.0)
        }
    };

    (delta_left, delta_top, scale_width, scale_height)
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
                let (delta_left, delta_top, scale_width, scale_height) = calculate_new_bounds(
                    constraint_h,
                    constraint_v,
                    &parent_bounds_before,
                    &parent_bounds_after,
                    &child_bounds_before,
                );

                // Translate position
                let th = parent_bounds_after.hv(delta_left);
                let tv = parent_bounds_after.vv(delta_top);
                let mut transform = Matrix::translate(th + tv);
                let child_bounds = child_bounds_before.transform(&transform);

                // Scale shape
                let center = child.center();
                let mut parent_transform = shape.transform;
                parent_transform.post_translate(center);
                parent_transform.pre_translate(-center);

                let parent_transform_inv = &parent_transform.invert().unwrap();
                let origin = parent_transform_inv.map_point(child_bounds.nw);

                let mut scale = Matrix::scale((scale_width, scale_height));
                scale.post_translate(origin);
                scale.post_concat(&parent_transform);
                scale.pre_translate(-origin);
                scale.pre_concat(&parent_transform_inv);

                transform.post_concat(&scale);
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
