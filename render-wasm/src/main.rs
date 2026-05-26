#[cfg(target_arch = "wasm32")]
mod emscripten;
mod error;
mod globals;
mod math;
mod mem;
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

use globals::{get_design_state, get_gpu_state, get_render_state};

use macros::wasm_error;
use math::{Bounds, Matrix};
use mem::SerializableResult;
use shapes::{StructureEntry, StructureEntryType, TransformEntry};
use skia_safe as skia;
use utils::uuid_from_u32_quartet;
use uuid::Uuid;

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_browser(browser: u8) -> Result<()> {
    with_state!(state, {
        state.set_browser(browser);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_render_options(debug: u32, dpr: f32) -> Result<()> {
    let render_state = get_render_state();
    render_state.set_debug_flags(debug);
    render_state.set_dpr(dpr)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_viewport_interest_area_threshold(
    viewport_interest_area_threshold: i32,
) -> Result<()> {
    let render_state = get_render_state();
    render_state.set_viewport_interest_area_threshold(viewport_interest_area_threshold);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_max_blocking_time_ms(max_blocking_time_ms: i32) -> Result<()> {
    let render_state = get_render_state();
    render_state.set_max_blocking_time_ms(max_blocking_time_ms);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_node_batch_threshold(node_batch_threshold: i32) -> Result<()> {
    let render_state = get_render_state();
    render_state.set_node_batch_threshold(node_batch_threshold);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_blur_downscale_threshold(blur_downscale_threshold: f32) -> Result<()> {
    let render_state = get_render_state();
    render_state.set_blur_downscale_threshold(blur_downscale_threshold);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_antialias_threshold(threshold: f32) -> Result<()> {
    get_render_state().set_antialias_threshold(threshold);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_max_atlas_texture_size(max_px: i32) -> Result<()> {
    get_render_state()
        .surfaces
        .set_max_atlas_texture_size(max_px);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_canvas_background(raw_color: u32) -> Result<()> {
    with_state!(state, {
        let color = skia::Color::new(raw_color);
        state.set_background_color(color);
        state.rebuild_tiles_shallow();
    });

    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render(timestamp: i32) -> Result<()> {
    with_state!(state, {
        state.rebuild_touched_tiles();
        // Drain the throttled modifier-tile invalidation accumulated
        // since the previous rAF. set_modifiers skips this work during
        // interactive_transform; we do it once here, with the current
        // modifier set, so the cost is paid once per rAF rather than
        // once per pointer move.
        if get_render_state().options.is_interactive_transform() {
            // Collect into an owned Vec to release the immutable borrow on
            // `state.shapes` before the mutable `rebuild_modifier_tiles` call.
            let ids = state.shapes.modifier_ids().to_vec();
            if !ids.is_empty() {
                state.rebuild_modifier_tiles(&ids)?;
            }
        }
        state
            .start_render_loop(timestamp)
            .map_err(|_| Error::RecoverableError("Error rendering".to_string()))?;
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync() -> Result<()> {
    with_state!(state, {
        state.rebuild_tiles();
        state
            .render_sync(0)
            .map_err(|_| Error::RecoverableError("Error rendering".to_string()))?;
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state!(state, {
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
            .render_sync_shape(&id, 0)
            .map_err(|e| Error::RecoverableError(e.to_string()))?;
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_from_cache(_: i32) -> Result<()> {
    with_state!(state, {
        // Don't cancel the animation frame — let the async render
        // continue populating the tile HashMap in the background.
        // process_animation_frame skips flush_and_submit in fast
        // mode so it won't present stale Target content.  The
        // tile HashMap is position-independent, so tiles rendered
        // for the old viewport can be reused by the next full
        // render at the new viewport position.
        state.render_from_cache();
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_preview_mode(enabled: bool) -> Result<()> {
    get_render_state().set_preview_mode(enabled);
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_preview() -> Result<()> {
    with_state!(state, {
        state.render_preview(performance::get_time());
    });
    Ok(())
}

/// Enter bulk-loading mode. While active, `state.loading` is `true`.
#[no_mangle]
#[wasm_error]
pub extern "C" fn begin_loading() -> Result<()> {
    with_state!(state, {
        state.loading = true;
    });
    Ok(())
}

/// Leave bulk-loading mode. Should be called after the first
/// render so the loading flag is available during that render.
#[no_mangle]
#[wasm_error]
pub extern "C" fn end_loading() -> Result<()> {
    with_state!(state, {
        state.loading = false;
    });
    Ok(())
}

/// Draw a full-screen loading overlay (background + "Loading…" text).
/// Called from CLJS right after begin_loading so the user sees
/// immediate feedback while shapes are being processed.
/// NOTE:
/// This is currently not being used, but it's set there for testing purposes on
/// upcoming tasks
#[no_mangle]
#[wasm_error]
pub extern "C" fn render_loading_overlay() -> Result<()> {
    get_render_state().render_loading_overlay();
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn process_animation_frame(timestamp: i32) -> Result<()> {
    let result = with_state!(state, { state.process_animation_frame(timestamp) });
    if let Err(err) = result {
        eprintln!("process_animation_frame error: {}", err);
    }
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn reset_canvas() -> Result<()> {
    get_render_state().reset_canvas();
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn resize_viewbox(width: i32, height: i32) -> Result<()> {
    get_render_state().resize(width, height)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) -> Result<()> {
    performance::begin_measure!("set_view");
    let render_state = get_render_state();
    render_state.set_view(zoom, x, y);
    performance::end_measure!("set_view");
    Ok(())
}

#[cfg(feature = "profile-macros")]
static mut VIEW_INTERACTION_START: i32 = 0;

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_start() -> Result<()> {
    #[cfg(feature = "profile-macros")]
    unsafe {
        VIEW_INTERACTION_START = performance::get_time();
    }
    performance::begin_measure!("set_view_start");
    get_render_state().options.set_fast_mode(true);
    performance::end_measure!("set_view_start");
    Ok(())
}

/// Finishes a view interaction (zoom or pan). Rebuilds the tile index
/// and, for zoom changes, invalidates the tile texture cache so the
/// subsequent render re-draws tiles at full quality.
/// For pure pan (same zoom), cached tiles are preserved so only
/// newly-visible tiles need rendering.
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_end() -> Result<()> {
    with_state!(state, {
        performance::begin_measure!("set_view_end");
        let render_state = get_render_state();
        render_state.options.set_fast_mode(false);
        render_state.cancel_animation_frame();

        let scale = render_state.get_scale();
        render_state
            .tile_viewbox
            .update(render_state.viewbox, scale);

        if render_state.options.is_profile_rebuild_tiles() {
            state.rebuild_tiles();
        } else if render_state.zoom_changed() {
            // Zoom changed: tile sizes differ so all cached tile
            // textures are invalid (wrong scale).  Rebuild the tile
            // index and clear the tile texture cache, but *preserve*
            // the cache canvas so render_from_cache can show a scaled
            // preview of the old content while new tiles render.
            render_state.rebuild_tile_index(&state.shapes);
            render_state.surfaces.invalidate_tile_cache();
        } else {
            // Pure pan at the same zoom level: tile contents have not
            // changed — only the viewport position moved. Update the
            // tile index (which tiles are in the interest area) but
            // keep cached tile textures so the render can blit them
            // instead of re-drawing every visible tile from scratch.
            render_state.rebuild_tile_index(&state.shapes);
        }
        performance::end_measure!("set_view_end");
    });
    Ok(())
}

/// Enter interactive transform mode (drag / resize / rotate of a
/// shape). Activates the same expensive-effect skipping as pan/zoom
/// (`fast_mode`) but keeps per-frame flushing enabled so the Target is
/// presented every rAF, and triggers atlas-backed backdrops so
/// invalidated tiles do not appear sequentially or flicker.
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_modifiers_start() -> Result<()> {
    performance::begin_measure!("set_modifiers_start");
    let render_state = get_render_state();
    render_state.options.set_fast_mode(true);
    render_state.options.set_interactive_transform(true);
    performance::end_measure!("set_modifiers_start");
    Ok(())
}

/// Leave interactive transform mode and cancel any pending async
/// render scheduled under it. The caller is responsible for triggering
/// a final full-quality render (typically via `_render`) once the
/// modifiers have been committed.
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_modifiers_end() -> Result<()> {
    performance::begin_measure!("set_modifiers_end");
    let render_state = get_render_state();
    render_state.options.set_fast_mode(false);
    render_state.options.set_interactive_transform(false);
    render_state.cancel_animation_frame();
    performance::end_measure!("set_modifiers_end");
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clear_focus_mode() -> Result<()> {
    with_state!(state, {
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
        .map(|data| Uuid::try_from(data).map_err(|e| Error::RecoverableError(e.to_string())))
        .collect::<Result<Vec<Uuid>>>()?;

    with_state!(state, {
        state.set_focus_mode(entries);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn init_shapes_pool(capacity: usize) -> Result<()> {
    with_state!(state, {
        state.init_shapes_pool(capacity);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn touch_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        state.touch_shape(shape_id);
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_parent(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state!(state, {
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

    with_state!(state, {
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
        .map(|data| Uuid::try_from(data).map_err(|e| Error::CriticalError(e.to_string())))
        .collect::<Result<Vec<Uuid>>>()?;

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
    let id = uuid_from_u32_quartet(a, b, c, d);
    let result = get_render_state().has_image(&id, is_thumbnail);
    Ok(result)
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

    let result_bound = with_state!(state, {
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

    let entries: Vec<StructureEntry> = bytes
        .chunks(44)
        .map(|chunk| {
            let data = chunk
                .try_into()
                .map_err(|_| Error::CriticalError("Invalid StructureEntry bytes".to_string()))?;
            Ok(StructureEntry::from_bytes(data))
        })
        .collect::<Result<Vec<_>>>()?;

    with_state!(state, {
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
                        .ok_or(Error::CriticalError(
                            "Parent not found for entry".to_string(),
                        ))?
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
    with_state!(state, {
        let render_state = get_render_state();
        let prev_modifier_ids = state.shapes.clean_all();
        // Skip the tile-cache cleanup during interactive transform: the
        // per-rAF `rebuild_modifier_tiles` in `render()` already evicts
        // the same tiles for the active modifier set, so the eviction
        // here is redundant and doubles the per-emission cost.
        if !prev_modifier_ids.is_empty() && !render_state.options.is_interactive_transform() {
            render_state.update_tiles_shapes(&prev_modifier_ids, &mut state.shapes)?;
        }
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_modifiers() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<TransformEntry> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::try_from(data).map_err(|e| Error::CriticalError(e.to_string())))
        .collect::<Result<Vec<_>>>()?;

    let mut modifiers = HashMap::new();
    let mut ids = Vec::<Uuid>::new();
    for entry in entries {
        modifiers.insert(entry.id, entry.transform);
        ids.push(entry.id);
    }

    with_state!(state, {
        state.set_modifiers(modifiers);
        if !get_render_state().options.is_interactive_transform() {
            state.rebuild_modifier_tiles(&ids)?;
        }
    });
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn start_temp_objects() -> Result<()> {
    get_design_state().start_temp_objects()?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn end_temp_objects() -> Result<()> {
    get_design_state().end_temp_objects()?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn get_shape_extrect(a: u32, b: u32, c: u32, d: u32) -> Result<*mut u8> {
    let id = uuid_from_u32_quartet(a, b, c, d);

    with_state!(state, {
        let Some(shape) = state.shapes.get(&id) else {
            return Err(Error::CriticalError("Shape not found".to_string()));
        };
        let extrect = get_render_state().get_cached_extrect(shape, &state.shapes, 1.0);
        let mut buf = Vec::with_capacity(16);
        buf.extend_from_slice(&extrect.x().to_le_bytes());
        buf.extend_from_slice(&extrect.y().to_le_bytes());
        buf.extend_from_slice(&extrect.width().to_le_bytes());
        buf.extend_from_slice(&extrect.height().to_le_bytes());
        Ok(mem::write_bytes(buf))
    })
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn render_shape_pixels(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    scale: f32,
) -> Result<*mut u8> {
    let id = uuid_from_u32_quartet(a, b, c, d);

    if !scale.is_finite() {
        return Err(Error::CriticalError("Scale is not finite".to_string()));
    }

    with_state!(state, {
        let (data, width, height) =
            state.render_shape_pixels(&id, scale, performance::get_time())?;

        let len = data.len() as u32;
        let mut buf = Vec::with_capacity(4 + data.len());
        buf.extend_from_slice(&len.to_le_bytes());
        buf.extend_from_slice(&width.to_le_bytes());
        buf.extend_from_slice(&height.to_le_bytes());
        buf.extend_from_slice(&data);
        Ok(mem::write_bytes(buf))
    })
}

#[no_mangle]
pub extern "C" fn render_stats() {
    get_render_state().print_stats();
}

#[no_mangle]
pub fn free_gpu_resources() {
    get_render_state().free_gpu_resources();
}

fn main() {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
}
