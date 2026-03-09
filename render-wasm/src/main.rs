#[cfg(target_arch = "wasm32")]
mod emscripten;
mod error;
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

use std::collections::HashMap;

#[allow(unused_imports)]
use crate::error::{Error, Result};
use macros::wasm_error;
use math::{Bounds, Matrix};
use mem::SerializableResult;
use shapes::{StructureEntry, StructureEntryType, TransformEntry};
use skia_safe as skia;
use state::State;
use utils::uuid_from_u32_quartet;
use uuid::Uuid;

pub(crate) static mut STATE: Option<Box<State>> = None;

#[macro_export]
macro_rules! with_state_mut {
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
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_ref()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

#[macro_export]
macro_rules! with_current_shape_mut {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");

        $state.touch_current();

        if let Some($shape) = $state.current_shape_mut() {
            $block
        }
    };
}

#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_ref()
        }
        .expect("Got an invalid state pointer");
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

#[macro_export]
macro_rules! with_state_mut_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
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

#[no_mangle]
#[wasm_error]
pub extern "C" fn init(width: i32, height: i32) -> Result<()> {
    let state_box = Box::new(State::new(width, height));
    unsafe {
        STATE = Some(state_box);
    }
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_browser(browser: u8) -> Result<()> {
    with_state_mut!(state, {
        state.set_browser(browser);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_up() -> Result<()> {
    with_state_mut!(state, {
        // Cancel the current animation frame if it exists so
        // it won't try to render without context
        let render_state = state.render_state_mut();
        render_state.cancel_animation_frame();
    });
    unsafe { STATE = None }
    mem::free_bytes()?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_render_options(debug: u32, dpr: f32) -> Result<()> {
    with_state_mut!(state, {
        let render_state = state.render_state_mut();
        render_state.set_debug_flags(debug);
        render_state.set_dpr(dpr);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_canvas_background(raw_color: u32) -> Result<()> {
    with_state_mut!(state, {
        let color = skia::Color::new(raw_color);
        state.set_background_color(color);
        state.rebuild_tiles_shallow();
    });

    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render(_: i32) -> Result<()> {
    with_state_mut!(state, {
        state.rebuild_touched_tiles();
        state
            .start_render_loop(performance::get_time())
            .expect("Error rendering");
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync() -> Result<()> {
    with_state_mut!(state, {
        state.rebuild_tiles();
        state
            .render_sync(performance::get_time())
            .expect("Error rendering");
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);

        // look for an existing root shape, and create it if missing
        let mut was_root_missing = false;
        if !state.shapes.has(&Uuid::nil()) {
            state.shapes.add_shape(Uuid::nil());
            was_root_missing = true;
        }

        if was_root_missing {
            state.set_parent_for_current_shape(Uuid::nil());
        }

        state.rebuild_tiles_from(Some(&id));
        state
            .render_sync_shape(&id, performance::get_time())
            .map_err(|e| Error::RecoverableError(e.to_string()))?;
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_from_cache(_: i32) -> Result<()> {
    with_state_mut!(state, {
        state.render_state.cancel_animation_frame();
        state.render_from_cache();
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_preview_mode(enabled: bool) -> Result<()> {
    with_state_mut!(state, {
        state.render_state.set_preview_mode(enabled);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_preview() -> Result<()> {
    with_state_mut!(state, {
        state.render_preview(performance::get_time());
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn process_animation_frame(timestamp: i32) -> Result<()> {
    let result = std::panic::catch_unwind(|| {
        with_state_mut!(state, {
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
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn reset_canvas() -> Result<()> {
    with_state_mut!(state, {
        state.render_state_mut().reset_canvas();
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn resize_viewbox(width: i32, height: i32) -> Result<()> {
    with_state_mut!(state, {
        state.resize(width, height);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) -> Result<()> {
    with_state_mut!(state, {
        performance::begin_measure!("set_view");
        let render_state = state.render_state_mut();
        render_state.set_view(zoom, x, y);
        performance::end_measure!("set_view");
    });
    Ok(())
}

#[cfg(feature = "profile-macros")]
static mut VIEW_INTERACTION_START: i32 = 0;

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_start() -> Result<()> {
    with_state_mut!(state, {
        #[cfg(feature = "profile-macros")]
        unsafe {
            VIEW_INTERACTION_START = performance::get_time();
        }
        performance::begin_measure!("set_view_start");
        state.render_state.options.set_fast_mode(true);
        performance::end_measure!("set_view_start");
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_end() -> Result<()> {
    with_state_mut!(state, {
        let _end_start = performance::begin_timed_log!("set_view_end");
        performance::begin_measure!("set_view_end");
        state.render_state.options.set_fast_mode(false);
        state.render_state.cancel_animation_frame();

        // Update tile_viewbox first so that get_tiles_for_shape uses the correct interest area
        // This is critical because we limit tiles to the interest area for optimization
        let scale = state.render_state.get_scale();
        state
            .render_state
            .tile_viewbox
            .update(state.render_state.viewbox, scale);

        // We rebuild the tile index on both pan and zoom because `get_tiles_for_shape`
        // clips each shape to the current `TileViewbox::interest_rect` (viewport-dependent).
        let _rebuild_start = performance::begin_timed_log!("rebuild_tiles");
        performance::begin_measure!("set_view_end::rebuild_tiles");
        if state.render_state.options.is_profile_rebuild_tiles() {
            state.rebuild_tiles();
        } else {
            state.rebuild_tiles_shallow();
        }
        performance::end_measure!("set_view_end::rebuild_tiles");
        performance::end_timed_log!("rebuild_tiles", _rebuild_start);

        state.render_state.sync_cached_viewbox();
        performance::end_measure!("set_view_end");
        performance::end_timed_log!("set_view_end", _end_start);
        #[cfg(feature = "profile-macros")]
        {
            let total_time = performance::get_time() - unsafe { VIEW_INTERACTION_START };
            performance::console_log!("[PERF] view_interaction: {}ms", total_time);
        }
    });
    Ok(())
}

/// Pre-compute and cache extrect (extended rectangle) for all shapes
/// by walking the tree bottom-up. This avoids expensive recursive
/// extrect calculations during the first tile rebuild after loading.
#[no_mangle]
#[wasm_error]
pub extern "C" fn warm_extrect_cache() -> Result<()> {
    with_state!(state, {
        performance::begin_measure!("warm_extrect_cache");
        state.warm_extrect_cache();
        performance::end_measure!("warm_extrect_cache");
    });
    Ok(())
}

/// Like set_view_end but uses chunked tile rebuild to avoid blocking
/// the main thread. Prepares the view state and starts the async
/// tile rebuild process. Call `tile_rebuild_step` in a rAF loop after this.
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_end_async() -> Result<()> {
    with_state_mut!(state, {
        performance::begin_measure!("set_view_end_async");
        state.render_state.options.set_fast_mode(false);
        state.render_state.cancel_animation_frame();

        let scale = state.render_state.get_scale();
        state
            .render_state
            .tile_viewbox
            .update(state.render_state.viewbox, scale);

        if state.render_state.options.is_profile_rebuild_tiles() {
            // Profile mode still uses sync rebuild
            state.rebuild_tiles();
        } else {
            state.start_tile_rebuild();
        }

        state.render_state.sync_cached_viewbox();
        performance::end_measure!("set_view_end_async");
    });
    Ok(())
}

/// Process a chunk of the tile rebuild. Returns 1 if more work remains, 0 if done.
#[no_mangle]
#[wasm_error]
pub extern "C" fn tile_rebuild_step(timestamp: i32) -> Result<i32> {
    let result = with_state_mut!(state, {
        if state.process_tile_rebuild_step(timestamp) {
            1
        } else {
            0
        }
    });
    Ok(result)
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clear_focus_mode() -> Result<()> {
    with_state_mut!(state, {
        state.clear_focus_mode();
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_focus_mode() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::try_from(data).unwrap())
        .collect();

    with_state_mut!(state, {
        state.set_focus_mode(entries);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn init_shapes_pool(capacity: usize) -> Result<()> {
    with_state_mut!(state, {
        state.init_shapes_pool(capacity);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn touch_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        state.touch_shape(shape_id);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_parent(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.set_parent_for_current_shape(id);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_masked_group(masked: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_masked(masked);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_selrect(left, top, right, bottom);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_clip_content(clip_content: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_clip(clip_content);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_rotation(rotation: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_rotation(rotation);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_transform(
    a: f32,
    b: f32,
    c: f32,
    d: f32,
    e: f32,
    f: f32,
) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_transform(a, b, c, d, e, f);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_child(id);
    });
    Ok(())
}

fn set_children_set(entries: Vec<Uuid>) -> Result<()> {
    let mut deleted = Vec::new();
    let mut parent_id = None;

    with_current_shape_mut!(state, |shape: &mut Shape| {
        parent_id = Some(shape.id);
        (_, deleted) = shape.compute_children_differences(&entries);
        shape.children = entries.clone();

        for id in entries {
            state.touch_shape(id);
            if let Some(children_shape) = state.shapes.get_mut(&id) {
                children_shape.set_deleted(false);
            }
        }
    });

    with_state_mut!(state, {
        let Some(parent_id) = parent_id else {
            return Err(Error::RecoverableError(
                "set_children_set: Parent ID not found".to_string(),
            ));
        };

        for id in deleted {
            state.delete_shape_children(parent_id, id);
            state.touch_shape(id);
        }
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_0() -> Result<()> {
    let entries = vec![];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_1(a1: u32, b1: u32, c1: u32, d1: u32) -> Result<()> {
    let entries = vec![uuid_from_u32_quartet(a1, b1, c1, d1)];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_2(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
    ];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_3(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
    ];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_4(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
    a4: u32,
    b4: u32,
    c4: u32,
    d4: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
        uuid_from_u32_quartet(a4, b4, c4, d4),
    ];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_5(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
    a4: u32,
    b4: u32,
    c4: u32,
    d4: u32,
    a5: u32,
    b5: u32,
    c5: u32,
    d5: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
        uuid_from_u32_quartet(a4, b4, c4, d4),
        uuid_from_u32_quartet(a5, b5, c5, d5),
    ];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children() -> Result<()> {
    let bytes = mem::bytes_or_empty();

    let entries: Vec<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::try_from(data).unwrap())
        .collect();

    set_children_set(entries)?;

    if !bytes.is_empty() {
        mem::free_bytes()?;
    }

    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn is_image_cached(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    is_thumbnail: bool,
) -> Result<bool> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let result = state.render_state().has_image(&id, is_thumbnail);
        Ok(result)
    })
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_svg_raw_content() -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let svg_raw_content = String::from_utf8(bytes)
            .map_err(|e| Error::RecoverableError(e.to_string()))?
            .trim_end_matches('\0')
            .to_string();
        shape.set_svg_raw_content(svg_raw_content);
    });

    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_opacity(opacity: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_opacity(opacity);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_hidden(hidden: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_hidden(hidden);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_corners(r1: f32, r2: f32, r3: f32, r4: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_corners((r1, r2, r3, r4));
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn get_selection_rect() -> Result<*mut u8> {
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

    let result_bound = with_state_mut!(state, {
        let bbs: Vec<_> = entries
            .iter()
            .flat_map(|id| state.shapes.get(id).map(|b| b.bounds()))
            .collect();

        if bbs.len() == 1 {
            bbs[0]
        } else {
            Bounds::join_bounds(&bbs)
        }
    });

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
    Ok(mem::write_bytes(bytes))
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_structure_modifiers() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(44)
        .map(|data| StructureEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state_mut!(state, {
        let mut structure = HashMap::new();
        let mut scale_content = HashMap::new();
        for entry in entries {
            match entry.entry_type {
                StructureEntryType::ScaleContent => {
                    let Some(shape) = state.shapes.get(&entry.id) else {
                        continue;
                    };
                    for id in shape.all_children(&state.shapes, true, true) {
                        scale_content.insert(id, entry.value);
                    }
                }
                _ => {
                    structure.entry(entry.parent).or_insert_with(Vec::new);
                    structure
                        .get_mut(&entry.parent)
                        .expect("Parent not found for entry")
                        .push(entry);
                }
            }
        }
        if !scale_content.is_empty() {
            state.shapes.set_scale_content(scale_content);
        }
        if !structure.is_empty() {
            state.shapes.set_structure(structure);
        }
    });

    mem::free_bytes()?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_modifiers() -> Result<()> {
    with_state_mut!(state, {
        state.shapes.clean_all();
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_modifiers() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::try_from(data).unwrap())
        .collect();

    let mut modifiers = HashMap::new();
    let mut ids = Vec::<Uuid>::new();
    for entry in entries {
        modifiers.insert(entry.id, entry.transform);
        ids.push(entry.id);
    }

    with_state_mut!(state, {
        state.set_modifiers(modifiers);
        state.rebuild_modifier_tiles(ids);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn start_temp_objects() -> Result<()> {
    unsafe {
        #[allow(static_mut_refs)]
        let mut state = STATE.take().expect("Got an invalid state pointer");
        state = Box::new(state.start_temp_objects());
        STATE = Some(state);
    }
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn end_temp_objects() -> Result<()> {
    unsafe {
        #[allow(static_mut_refs)]
        let mut state = STATE.take().expect("Got an invalid state pointer");
        state = Box::new(state.end_temp_objects());
        STATE = Some(state);
    }
    Ok(())
}

fn main() {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
}
