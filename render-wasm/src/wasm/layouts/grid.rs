use macros::ToJs;

use crate::mem;
use crate::shapes::{GridCell, GridDirection, GridTrack, GridTrackType};
use crate::uuid::Uuid;
use crate::{uuid_from_u32_quartet, with_current_shape_mut, with_state, with_state_mut, STATE};

use super::align;

#[derive(Debug)]
#[repr(C, align(1))]
struct RawGridCell {
    row: i32,
    row_span: i32,
    column: i32,
    column_span: i32,
    align_self: u8,
    justify_self: u8,
    _padding: u16,
    shape_id_a: u32,
    shape_id_b: u32,
    shape_id_c: u32,
    shape_id_d: u32,
}

impl From<[u8; size_of::<RawGridCell>()]> for RawGridCell {
    fn from(bytes: [u8; size_of::<RawGridCell>()]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl From<RawGridCell> for GridCell {
    fn from(raw: RawGridCell) -> Self {
        let raw_justify_self = super::align::RawJustifySelf::from(raw.justify_self);
        let raw_align_self = super::align::RawAlignSelf::from(raw.align_self);
        let shape_id = uuid_from_u32_quartet(
            raw.shape_id_a,
            raw.shape_id_b,
            raw.shape_id_c,
            raw.shape_id_d,
        );

        Self {
            row: raw.row,
            row_span: raw.row_span,
            column: raw.column,
            column_span: raw.column_span,
            align_self: raw_align_self.try_into().ok(),
            justify_self: raw_justify_self.try_into().ok(),
            shape: if shape_id != Uuid::nil() {
                Some(shape_id)
            } else {
                None
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawGridDirection {
    Row = 0,
    Column = 1,
}

impl From<u8> for RawGridDirection {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawGridDirection> for GridDirection {
    fn from(value: RawGridDirection) -> Self {
        match value {
            RawGridDirection::Row => Self::Row,
            RawGridDirection::Column => Self::Column,
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawGridTrackType {
    Percent = 0,
    Flex = 1,
    Auto = 2,
    Fixed = 3,
}

impl From<u8> for RawGridTrackType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawGridTrackType> for GridTrackType {
    fn from(value: RawGridTrackType) -> Self {
        match value {
            RawGridTrackType::Percent => Self::Percent,
            RawGridTrackType::Flex => Self::Flex,
            RawGridTrackType::Auto => Self::Auto,
            RawGridTrackType::Fixed => Self::Fixed,
        }
    }
}

#[derive(Debug)]
#[repr(C, align(4))]
#[allow(dead_code)]
pub struct RawGridTrack {
    track_type: RawGridTrackType,
    _padding: [u8; 3],
    value: f32,
}

impl From<[u8; size_of::<RawGridTrack>()]> for RawGridTrack {
    fn from(bytes: [u8; size_of::<RawGridTrack>()]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl From<RawGridTrack> for GridTrack {
    fn from(raw: RawGridTrack) -> Self {
        Self {
            track_type: raw.track_type.into(),
            value: raw.value,
        }
    }
}

#[no_mangle]
pub extern "C" fn set_grid_layout_data(
    dir: u8,
    row_gap: f32,
    column_gap: f32,
    align_items: u8,
    align_content: u8,
    justify_items: u8,
    justify_content: u8,
    padding_top: f32,
    padding_right: f32,
    padding_bottom: f32,
    padding_left: f32,
) {
    let dir = RawGridDirection::from(dir);
    let align_items = align::RawAlignItems::from(align_items);
    let align_content = align::RawAlignContent::from(align_content);
    let justify_items = align::RawJustifyItems::from(justify_items);
    let justify_content = align::RawJustifyContent::from(justify_content);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_layout_data(
            dir.into(),
            row_gap,
            column_gap,
            align_items.into(),
            align_content.into(),
            justify_items.into(),
            justify_content.into(),
            padding_top,
            padding_right,
            padding_bottom,
            padding_left,
        );
    });
}

#[no_mangle]
pub extern "C" fn set_grid_columns() {
    let bytes = mem::bytes();

    let entries: Vec<GridTrack> = bytes
        .chunks(size_of::<RawGridTrack>())
        .map(|data| data.try_into().unwrap())
        .map(|data: [u8; size_of::<RawGridTrack>()]| RawGridTrack::from(data).into())
        .collect();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_columns(entries);
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_grid_rows() {
    let bytes = mem::bytes();

    let entries: Vec<GridTrack> = bytes
        .chunks(size_of::<RawGridTrack>())
        .map(|data| data.try_into().unwrap())
        .map(|data: [u8; size_of::<RawGridTrack>()]| RawGridTrack::from(data).into())
        .collect();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_rows(entries);
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_grid_cells() {
    let bytes = mem::bytes();

    let cells: Vec<RawGridCell> = bytes
        .chunks(size_of::<RawGridCell>())
        .map(|data| data.try_into().expect("Invalid grid cell data"))
        .map(|data: [u8; size_of::<RawGridCell>()]| RawGridCell::from(data))
        .collect();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_cells(cells.into_iter().map(|raw| raw.into()).collect());
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn show_grid(a: u32, b: u32, c: u32, d: u32) {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.render_state.show_grid = Some(id);
    });
}

#[no_mangle]
pub extern "C" fn hide_grid() {
    with_state_mut!(state, {
        state.render_state.show_grid = None;
    });
}

#[no_mangle]
pub extern "C" fn get_grid_coords(pos_x: f32, pos_y: f32) -> *mut u8 {
    let row: i32;
    let col: i32;
    with_state!(state, {
        if let Some((r, c)) = state.get_grid_coords(pos_x, pos_y) {
            row = r;
            col = c;
        } else {
            row = -1;
            col = -1;
        };
    });
    let mut bytes = vec![0; 8];
    bytes[0..4].clone_from_slice(&row.to_le_bytes());
    bytes[4..8].clone_from_slice(&col.to_le_bytes());
    mem::write_bytes(bytes)
}
