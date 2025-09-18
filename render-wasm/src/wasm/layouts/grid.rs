use crate::mem;
use crate::shapes::{self};
use crate::{uuid_from_u32_quartet, with_current_shape_mut, with_state, with_state_mut, STATE};

use super::align;

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

    let entries: Vec<_> = bytes
        .chunks(size_of::<shapes::RawGridCell>())
        .map(|data| shapes::RawGridCell::from_bytes(data.try_into().unwrap()))
        .collect();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_cells(entries);
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
