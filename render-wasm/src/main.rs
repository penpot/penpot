#[cfg(target_arch = "wasm32")]
mod emscripten;
mod math;
mod mem;
mod options;
mod performance;
mod render;
mod shapes;
mod state;
mod tiles;
mod utils;
mod uuid;
mod view;
mod wapi;
mod wasm;

use indexmap::IndexSet;
use math::{Bounds, Matrix};
use mem::SerializableResult;
use shapes::{
    BoolType, ConstraintH, ConstraintV, StructureEntry, StructureEntryType, TransformEntry, Type,
};
use skia_safe as skia;
use state::State;
use utils::uuid_from_u32_quartet;
use uuid::Uuid;

pub(crate) static mut STATE: Option<Box<State>> = None;

#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

/// This is called from JS after the WebGL context has been created.
#[no_mangle]
pub extern "C" fn init(width: i32, height: i32) {
    let state_box = Box::new(State::new(width, height, 2048));
    unsafe {
        STATE = Some(state_box);
    }
}

#[no_mangle]
pub extern "C" fn clean_up() {
    unsafe { STATE = None }
    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn clear_drawing_cache() {
    with_state!(state, {
        state.rebuild_tiles();
    });
}

#[no_mangle]
pub extern "C" fn set_render_options(debug: u32, dpr: f32) {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.set_debug_flags(debug);
        render_state.set_dpr(dpr);
    });
}

#[no_mangle]
pub extern "C" fn set_canvas_background(raw_color: u32) {
    with_state!(state, {
        let color = skia::Color::new(raw_color);
        state.set_background_color(color);
    });
}

#[no_mangle]
pub extern "C" fn render(_: i32) {
    with_state!(state, {
        state
            .start_render_loop(performance::get_time())
            .expect("Error rendering");
    });
}

#[no_mangle]
pub extern "C" fn render_from_cache(_: i32) {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.render_from_cache();
    });
}

#[no_mangle]
pub extern "C" fn process_animation_frame(timestamp: i32) {
    let result = std::panic::catch_unwind(|| {
        with_state!(state, {
            state
                .process_animation_frame(timestamp)
                .expect("Error processing animation frame");
        });
    });

    match result {
        Ok(_) => {}
        Err(err) => {
            match err.downcast_ref::<String>() {
                Some(message) => println!("process_animation_frame error: {}", message),
                None => println!("process_animation_frame error: {:?}", err),
            }
            std::panic::resume_unwind(err);
        }
    }
}

#[no_mangle]
pub extern "C" fn reset_canvas() {
    with_state!(state, {
        state.render_state().reset_canvas();
    });
}

#[no_mangle]
pub extern "C" fn resize_viewbox(width: i32, height: i32) {
    with_state!(state, {
        state.resize(width, height);
    });
}

#[no_mangle]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) {
    with_state!(state, {
        let render_state = state.render_state();
        render_state.viewbox.set_all(zoom, x, y);
        with_state!(state, {
            // We can have renders in progress
            state.render_state.cancel_animation_frame();
            if state.render_state.options.is_profile_rebuild_tiles() {
                state.rebuild_tiles();
            } else {
                state.rebuild_tiles_shallow();
            }
        });
    });
}

#[no_mangle]
pub extern "C" fn init_shapes_pool(capacity: usize) {
    with_state!(state, {
        state.init_shapes_pool(capacity);
    });
}

#[no_mangle]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
}

#[no_mangle]
pub extern "C" fn set_parent(a: u32, b: u32, c: u32, d: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.set_parent(id);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_masked_group(masked: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_masked(masked);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_bool_type(raw_bool_type: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_bool_type(BoolType::from(raw_bool_type));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_type(shape_type: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_shape_type(Type::from(shape_type));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) {
    with_state!(state, {
        state.set_selrect_for_current_shape(left, top, right, bottom);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_clip_content(clip_content: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_clip(clip_content);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_rotation(rotation: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_rotation(rotation);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_transform(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_transform(a, b, c, d, e, f);
    });
}

#[no_mangle]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) {
    with_current_shape!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_child(id);
    });
}

#[no_mangle]
pub extern "C" fn set_children() {
    let bytes = mem::bytes_or_empty();

    let entries: IndexSet<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::from_bytes(data.try_into().unwrap()))
        .collect();

    let mut deleted = IndexSet::new();

    with_current_shape!(state, |shape: &mut Shape| {
        (_, deleted) = shape.compute_children_differences(&entries);
        shape.children = entries.clone();
    });

    with_state!(state, {
        for id in deleted {
            state.delete_shape(id);
        }
    });

    if !bytes.is_empty() {
        mem::free_bytes();
    }
}

#[no_mangle]
pub extern "C" fn store_image(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let image_bytes = mem::bytes();

        if let Err(msg) = state.render_state().add_image(id, &image_bytes) {
            eprintln!("{}", msg);
        }

        mem::free_bytes();
    });
}

#[no_mangle]
pub extern "C" fn is_image_cached(a: u32, b: u32, c: u32, d: u32) -> bool {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.render_state().has_image(&id)
    })
}

#[no_mangle]
pub extern "C" fn set_shape_svg_raw_content() {
    with_current_shape!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let svg_raw_content = String::from_utf8(bytes)
            .unwrap()
            .trim_end_matches('\0')
            .to_string();
        shape
            .set_svg_raw_content(svg_raw_content)
            .expect("Failed to set svg raw content");
    });
}

#[no_mangle]
pub extern "C" fn set_shape_blend_mode(mode: i32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_blend_mode(render::BlendMode::from(mode));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_opacity(opacity: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_opacity(opacity);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_h(constraint: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_constraint_h(ConstraintH::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_constraint_v(constraint: u8) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_constraint_v(ConstraintV::from(constraint));
    });
}

#[no_mangle]
pub extern "C" fn set_shape_hidden(hidden: bool) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_hidden(hidden);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_blur(blur_type: u8, hidden: bool, value: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_blur(blur_type, hidden, value);
    });
}

#[no_mangle]
pub extern "C" fn set_shape_corners(r1: f32, r2: f32, r3: f32, r4: f32) {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_corners((r1, r2, r3, r4));
    });
}

#[no_mangle]
pub extern "C" fn propagate_modifiers(pixel_precision: bool) -> *mut u8 {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state!(state, {
        let result = shapes::propagate_modifiers(state, &entries, pixel_precision);
        mem::write_vec(result)
    })
}

#[no_mangle]
pub extern "C" fn get_selection_rect() -> *mut u8 {
    let bytes = mem::bytes();

    let entries: Vec<Uuid> = bytes
        .chunks(16)
        .map(|bytes| {
            uuid_from_u32_quartet(
                u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
                u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
                u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
                u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            )
        })
        .collect();

    with_state!(state, {
        let bbs: Vec<_> = entries
            .iter()
            .flat_map(|id| {
                let default = Matrix::default();
                let modifier = state.modifiers.get(id).unwrap_or(&default);
                state.shapes.get(id).map(|b| b.bounds().transform(modifier))
            })
            .collect();

        let result_bound = if bbs.len() == 1 {
            bbs[0]
        } else {
            Bounds::join_bounds(&bbs)
        };

        let width = result_bound.width();
        let height = result_bound.height();
        let center = result_bound.center();
        let transform = result_bound.transform_matrix().unwrap_or(Matrix::default());

        let mut bytes = vec![0; 40];
        bytes[0..4].clone_from_slice(&width.to_le_bytes());
        bytes[4..8].clone_from_slice(&height.to_le_bytes());
        bytes[8..12].clone_from_slice(&center.x.to_le_bytes());
        bytes[12..16].clone_from_slice(&center.y.to_le_bytes());
        bytes[16..20].clone_from_slice(&transform[0].to_le_bytes());
        bytes[20..24].clone_from_slice(&transform[3].to_le_bytes());
        bytes[24..28].clone_from_slice(&transform[1].to_le_bytes());
        bytes[28..32].clone_from_slice(&transform[4].to_le_bytes());
        bytes[32..36].clone_from_slice(&transform[2].to_le_bytes());
        bytes[36..40].clone_from_slice(&transform[5].to_le_bytes());
        mem::write_bytes(bytes)
    })
}

#[no_mangle]
pub extern "C" fn set_structure_modifiers() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(44)
        .map(|data| StructureEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state!(state, {
        for entry in entries {
            match entry.entry_type {
                StructureEntryType::ScaleContent => {
                    let Some(shape) = state.shapes.get(&entry.id) else {
                        continue;
                    };
                    for id in shape.all_children_with_self(&state.shapes) {
                        state.scale_content.insert(id, entry.value);
                    }
                }
                _ => {
                    state.structure.entry(entry.parent).or_insert_with(Vec::new);
                    state
                        .structure
                        .get_mut(&entry.parent)
                        .expect("Parent not found for entry")
                        .push(entry);
                }
            }
        }
    });

    mem::free_bytes();
}

#[no_mangle]
pub extern "C" fn clean_modifiers() {
    with_state!(state, {
        state.structure.clear();
        state.scale_content.clear();
        state.modifiers.clear();
    });
}

#[no_mangle]
pub extern "C" fn set_modifiers() {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state!(state, {
        for entry in entries {
            state.modifiers.insert(entry.id, entry.transform);
        }
        state.rebuild_modifier_tiles();
    });
}

#[no_mangle]
pub extern "C" fn add_shape_shadow(
    raw_color: u32,
    blur: f32,
    spread: f32,
    x: f32,
    y: f32,
    raw_style: u8,
    hidden: bool,
) {
    with_current_shape!(state, |shape: &mut Shape| {
        let color = skia::Color::new(raw_color);
        let style = shapes::ShadowStyle::from(raw_style);
        let shadow = shapes::Shadow::new(color, blur, spread, (x, y), style, hidden);
        shape.add_shadow(shadow);
    });
}

#[no_mangle]
pub extern "C" fn clear_shape_shadows() {
    with_current_shape!(state, |shape: &mut Shape| {
        shape.clear_shadows();
    });
}

#[no_mangle]
pub extern "C" fn update_shape_tiles() {
    with_state!(state, {
        state.update_tile_for_current_shape();
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

    with_current_shape!(state, |shape: &mut Shape| {
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

    with_current_shape!(state, |shape: &mut Shape| {
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

    with_current_shape!(state, |shape: &mut Shape| {
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

    with_current_shape!(state, |shape: &mut Shape| {
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

    with_current_shape!(state, |shape: &mut Shape| {
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

    with_current_shape!(state, |shape: &mut Shape| {
        shape.set_grid_cells(entries);
    });

    mem::free_bytes();
}

fn main() {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
}
