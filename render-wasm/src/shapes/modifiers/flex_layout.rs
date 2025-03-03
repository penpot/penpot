use crate::math::{Bounds, Matrix};
use crate::shapes::{FlexData, LayoutData, Shape, TransformEntry};

pub fn propagate_shape_constraints(
    layout_data: &LayoutData,
    flex_data: &FlexData,
    _parent_bounds_before: &Bounds,
    _parent_bounds_after: &Bounds,
    _child: &Shape,
    _transform: Matrix,
    _result: &mut Vec<TransformEntry>,
) {
    println!("{layout_data:?}, {flex_data:?}");
}
