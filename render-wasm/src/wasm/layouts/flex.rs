use crate::shapes::{FlexDirection, WrapType};
use crate::{with_current_shape_mut, STATE};
use macros::ToJs;

use super::align;

#[derive(Debug, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawFlexDirection {
    Row = 0,
    RowReverse = 1,
    Column = 2,
    ColumnReverse = 3,
}

impl From<u8> for RawFlexDirection {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawFlexDirection> for FlexDirection {
    fn from(value: RawFlexDirection) -> Self {
        match value {
            RawFlexDirection::Row => FlexDirection::Row,
            RawFlexDirection::RowReverse => FlexDirection::RowReverse,
            RawFlexDirection::Column => FlexDirection::Column,
            RawFlexDirection::ColumnReverse => FlexDirection::ColumnReverse,
        }
    }
}

#[derive(Debug, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawWrapType {
    Wrap = 0,
    Nowrap = 1, // odd casing to comply with cljs value
}

impl From<u8> for RawWrapType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawWrapType> for WrapType {
    fn from(value: RawWrapType) -> Self {
        match value {
            RawWrapType::Wrap => WrapType::Wrap,
            RawWrapType::Nowrap => WrapType::NoWrap,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_flex_layout_data(
    dir: u8,
    row_gap: f32,
    column_gap: f32,
    align_items: u8,
    align_content: u8,
    justify_items: u8,
    justify_content: u8,
    wrap_type: u8,
    padding_top: f32,
    padding_right: f32,
    padding_bottom: f32,
    padding_left: f32,
) {
    let dir = RawFlexDirection::from(dir);
    let align_items = align::RawAlignItems::from(align_items);
    let align_content = align::RawAlignContent::from(align_content);
    let justify_items = align::RawJustifyItems::from(justify_items);
    let justify_content = align::RawJustifyContent::from(justify_content);
    let wrap_type = RawWrapType::from(wrap_type);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_data(
            dir.into(),
            row_gap,
            column_gap,
            align_items.into(),
            align_content.into(),
            justify_items.into(),
            justify_content.into(),
            wrap_type.into(),
            padding_top,
            padding_right,
            padding_bottom,
            padding_left,
        );
    });
}
