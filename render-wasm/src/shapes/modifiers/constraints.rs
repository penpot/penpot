use crate::math::{Bounds, Matrix};
use crate::shapes::{ConstraintH, ConstraintV};

pub fn calculate_resize(
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

pub fn calculate_displacement(
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

pub fn propagate_shape_constraints(
    parent_bounds_before: &Bounds,
    parent_bounds_after: &Bounds,
    child_bounds_before: &Bounds,
    constraint_h: ConstraintH,
    constraint_v: ConstraintV,
    transform: Matrix,
    ignore_constrainst: bool,
) -> Matrix {
    // if the constrains are scale & scale or the transform has only moves we
    // can propagate as is
    if (ignore_constrainst
        || constraint_h == ConstraintH::Scale && constraint_v == ConstraintV::Scale)
        || transform.is_translate()
    {
        return transform;
    }

    let mut transform = transform;
    let mut child_bounds_after = child_bounds_before.transform(&transform);

    // Scale shape
    if let Some((scale_width, scale_height)) = calculate_resize(
        constraint_h,
        constraint_v,
        parent_bounds_before,
        parent_bounds_after,
        child_bounds_before,
        &child_bounds_after,
    ) {
        let center = child_bounds_before.center();

        let mut parent_transform = parent_bounds_after.transform_matrix().unwrap_or_default();
        parent_transform.post_translate(center);
        parent_transform.pre_translate(-center);

        let parent_transform_inv = &parent_transform.invert().unwrap();
        let origin = parent_transform_inv.map_point(child_bounds_after.nw);

        let mut scale = Matrix::scale((scale_width, scale_height));
        scale.post_translate(origin);
        scale.post_concat(&parent_transform);
        scale.pre_translate(-origin);
        scale.pre_concat(parent_transform_inv);

        child_bounds_after.transform_mut(&scale);
        transform.post_concat(&scale);
    }

    // Translate position
    if let Some((delta_x, delta_y)) = calculate_displacement(
        constraint_h,
        constraint_v,
        parent_bounds_before,
        parent_bounds_after,
        child_bounds_before,
        &child_bounds_after,
    ) {
        let th = parent_bounds_after.hv(delta_x);
        let tv = parent_bounds_after.vv(delta_y);
        transform.post_concat(&Matrix::translate(th + tv));
    }

    transform
}
