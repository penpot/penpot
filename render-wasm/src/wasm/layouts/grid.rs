use crate::mem;
use crate::shapes::{self, GridCell};
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
    has_align_self: u8, // FIXME: remove this field
    align_self: u8,
    justify_self: u8,
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
            align_self: if raw.has_align_self == 1 {
                shapes::AlignSelf::from_u8(raw.align_self)
            } else {
                None
            },
            justify_self: match raw_justify_self {
                super::align::RawJustifySelf::None => None,
                _ => Some(crate::wasm::layouts::RawJustifySelf::from(raw.justify_self).into()),
            },
            shape: if shape_id != Uuid::nil() {
                Some(shape_id)
            } else {
                None
            },
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
    let dir = shapes::GridDirection::from_u8(dir);
    let align_items = align::RawAlignItems::from(align_items);
    let align_content = align::RawAlignContent::from(align_content);
    let justify_items = align::RawJustifyItems::from(justify_items);
    let justify_content = align::RawJustifyContent::from(justify_content);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_layout_data(
            dir,
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

    let entries: Vec<_> = bytes
        .chunks(size_of::<shapes::RawGridTrack>())
        .map(|data| shapes::RawGridTrack::from_bytes(data.try_into().unwrap()))
        .collect();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_columns(entries);
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn set_grid_rows() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<shapes::RawGridTrack>())
        .map(|data| shapes::RawGridTrack::from_bytes(data.try_into().unwrap()))
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
