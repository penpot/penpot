use std::collections::HashMap;

use crate::error::{Error, Result};
use crate::math::{Bounds, Matrix};
use crate::shapes::Shape;
use crate::uuid::Uuid;

pub trait GetBounds {
    fn find(&self, shape: &Shape) -> Bounds;
}

impl GetBounds for HashMap<Uuid, Bounds> {
    fn find(&self, shape: &Shape) -> Bounds {
        self.get(&shape.id).copied().unwrap_or(shape.bounds())
    }
}

/// Scale that auto-sizes a layout along its own axes, keeping its top-right
/// corner when `from_right` (rtl text growth) and its top-left one otherwise.
pub fn auto_size_matrix(
    layout_bounds: &Bounds,
    scale_width: f32,
    scale_height: f32,
    from_right: bool,
) -> Result<Matrix> {
    let parent_transform = layout_bounds.transform_matrix().unwrap_or_default();

    let parent_transform_inv = &parent_transform.invert().ok_or(Error::CriticalError(
        "Failed to invert parent transform".to_string(),
    ))?;
    let anchor = if from_right {
        layout_bounds.ne
    } else {
        layout_bounds.nw
    };
    let origin = parent_transform_inv.map_point(anchor);

    let mut scale = Matrix::scale((scale_width, scale_height));
    scale.post_translate(origin);
    scale.post_concat(&parent_transform);
    scale.pre_translate(-origin);
    scale.pre_concat(parent_transform_inv);
    Ok(scale)
}
