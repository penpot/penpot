use crate::mem;
use crate::shapes::{self, ConstraintH, ConstraintV, VerticalAlign};
use crate::{uuid_from_u32_quartet, with_current_shape_mut, with_state, with_state_mut, STATE};

#[no_mangle]
pub extern "C" fn set_shape_constraint_h(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_constraint_h(ConstraintH::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_v(constraint: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_constraint_v(ConstraintV::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_constraints() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_constraints();
    });
}

#[no_mangle]
pub extern "C" fn set_shape_vertical_align(align: u8) {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_vertical_align(VerticalAlign::from(align));
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_layout();
    });
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
    let dir = shapes::FlexDirection::from_u8(dir);
    let align_items = shapes::AlignItems::from_u8(align_items);
    let align_content = shapes::AlignContent::from_u8(align_content);
    let justify_items = shapes::JustifyItems::from_u8(justify_items);
    let justify_content = shapes::JustifyContent::from_u8(justify_content);
    let wrap_type = shapes::WrapType::from_u8(wrap_type);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_data(
            dir,
            row_gap,
            column_gap,
            align_items,
            align_content,
            justify_items,
            justify_content,
            wrap_type,
            padding_top,
            padding_right,
            padding_bottom,
            padding_left,
        );
    });
}

#[no_mangle]
pub extern "C" fn set_layout_child_data(
    margin_top: f32,
    margin_right: f32,
    margin_bottom: f32,
    margin_left: f32,
    h_sizing: u8,
    v_sizing: u8,
    has_max_h: bool,
    max_h: f32,
    has_min_h: bool,
    min_h: f32,
    has_max_w: bool,
    max_w: f32,
    has_min_w: bool,
    min_w: f32,
    has_align_self: bool,
    align_self: u8,
    is_absolute: bool,
    z_index: i32,
) {
    let h_sizing = shapes::Sizing::from_u8(h_sizing);
    let v_sizing = shapes::Sizing::from_u8(v_sizing);
    let max_h = if has_max_h { Some(max_h) } else { None };
    let min_h = if has_min_h { Some(min_h) } else { None };
    let max_w = if has_max_w { Some(max_w) } else { None };
    let min_w = if has_min_w { Some(min_w) } else { None };
    let align_self = if has_align_self {
        shapes::AlignSelf::from_u8(align_self)
    } else {
        None
    };

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_flex_layout_child_data(
            margin_top,
            margin_right,
            margin_bottom,
            margin_left,
            h_sizing,
            v_sizing,
            max_h,
            min_h,
            max_w,
            min_w,
            align_self,
            is_absolute,
            z_index,
        );
    });
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
    let align_items = shapes::AlignItems::from_u8(align_items);
    let align_content = shapes::AlignContent::from_u8(align_content);
    let justify_items = shapes::JustifyItems::from_u8(justify_items);
    let justify_content = shapes::JustifyContent::from_u8(justify_content);

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_grid_layout_data(
            dir,
            row_gap,
            column_gap,
            align_items,
            align_content,
            justify_items,
            justify_content,
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
