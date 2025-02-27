use crate::math::{Matrix, Bounds};
use crate::shapes::{Shape, TransformEntry, LayoutData, FlexData};

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
