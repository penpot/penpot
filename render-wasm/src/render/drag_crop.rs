use skia_safe::{self as skia, Rect};

use super::RenderState;
use super::SurfaceId;
use crate::get_gpu_state;
use crate::math;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

pub struct InteractiveDragCrop {
    pub src_doc_bounds: Rect,
    pub src_selrect: Rect,
    /// Viewbox origin (doc-space) at capture time.
    pub capture_vb_left: f32,
    pub capture_vb_top: f32,
    /// Backbuffer pixel origin used for `snapshot_rect` (so we can do 1:1 blits).
    pub capture_src_left: i32,
    pub capture_src_top: i32,
    pub image: skia::Image,
}

/// Chooses a window inside the full workspace-pixel crop `[0, out_w) × [0, out_h)` with each side
/// at most `max_side_px` (**without scaling**): centered on the projection of
/// `viewport_doc ∩ src_doc_bounds`, or on the full crop if that intersection is empty.
/// `max_side_px` should match [`GpuState::max_texture_size`] (same budget as the atlas).
#[allow(clippy::too_many_arguments)]
pub fn drag_crop_snapshot_window_px(
    max_side_px: i32,
    out_w: i32,
    out_h: i32,
    viewport_doc: Rect,
    vb_left: f32,
    vb_top: f32,
    scale: f32,
    src_left_px: i32,
    src_top_px: i32,
    src_doc_bounds: Rect,
) -> (i32, i32, i32, i32) {
    let cap = max_side_px.max(1);
    if out_w <= cap && out_h <= cap {
        return (0, 0, out_w, out_h);
    }
    let win_w = out_w.min(cap);
    let win_h = out_h.min(cap);

    let mut vis = viewport_doc;
    let has_vis = vis.intersect(src_doc_bounds);
    let (cx, cy) = if !has_vis || vis.is_empty() {
        (out_w as f32 * 0.5, out_h as f32 * 0.5)
    } else {
        let lx0 = (vis.left - vb_left) * scale - src_left_px as f32;
        let ly0 = (vis.top - vb_top) * scale - src_top_px as f32;
        let lx1 = (vis.right - vb_left) * scale - src_left_px as f32;
        let ly1 = (vis.bottom - vb_top) * scale - src_top_px as f32;
        ((lx0 + lx1) * 0.5, (ly0 + ly1) * 0.5)
    };

    let mut ox = (cx - win_w as f32 * 0.5).round() as i32;
    let mut oy = (cy - win_h as f32 * 0.5).round() as i32;
    ox = ox.clamp(0, out_w - win_w);
    oy = oy.clamp(0, out_h - win_h);
    (ox, oy, win_w, win_h)
}

/// Decide whether a top-level node can be served from `backbuffer_crop_cache` during an
/// interactive transform (drag/resize/rotate).
///
/// We only reuse cached pixels when it is safe and visually correct:
/// - **Top-level only**: cache entries are built for direct children of the root.
/// - **Moved node**: only allow cache reuse for *pure translations* (no scale/rotate/skew),
///   because other transforms would require resampling and can diverge from the live render.
/// - **Other cached nodes**: if the moving bounds overlap this cached crop, invalidate it so
///   we don't show stale content while something moves over/inside it.
pub fn should_use_cached_top_level_during_interactive(
    render_state: &mut RenderState,
    node_id: Uuid,
    tree: ShapesPoolRef,
    moved_ids: &[Uuid],
    moved_bounds: Option<Rect>,
) -> bool {
    if !render_state.backbuffer_crop_cache.contains_key(&node_id) {
        return false;
    }
    let Some(raw) = tree.get_raw(&node_id) else {
        return false;
    };
    if raw.parent_id != Some(Uuid::nil()) {
        return false;
    }

    // If this top-level shape itself is being moved, always allow using its cached pixels.
    // BUT only for pure translations. For non-translation transforms (scale/rotate/skew),
    // cached pixels won't match the live result (and may require resampling), so render live.
    if moved_ids.contains(&node_id) {
        let Some(m) = tree.get_modifier(&node_id) else {
            return false;
        };
        // Only allow using the cached pixels for pure translations.
        // For non-translation transforms (scale/rotate/skew), cached pixels won't match.
        // If the transform is the identity means a reflow, we need to redraw as well.
        if math::identitish(m) || !math::is_move_only_matrix(m) {
            return false;
        }

        // Additionally require this node to be safe to serve from a rectangular backbuffer
        // crop while moving; otherwise it must be rendered live (e.g. text, overflow frames).
        return tree
            .get(&node_id)
            .is_some_and(|s| s.is_safe_for_drag_crop_cache(tree));
    }

    // If the moving content overlaps this cached crop, do not use the cached pixels
    // for this frame. We intentionally keep the cache entry: overlap is typically
    // transient during drag, and once the moving content leaves the area the crop
    // becomes valid again (stationary shape unchanged).
    if let Some(moved) = moved_bounds {
        let intersects = render_state
            .backbuffer_crop_cache
            .get(&node_id)
            .is_some_and(|crop| moved.intersects(crop.src_doc_bounds));

        if intersects {
            return false;
        }
    }
    true
}

pub fn rebuild_backbuffer_crop_cache(
    render_state: &mut RenderState,
    tree: ShapesPoolRef,
) {
    render_state.backbuffer_crop_cache.clear();

    // Collect candidate shapes that are "recortable" and visible in the current viewport.

    // This is intentionally conservative; we only cache shapes that do not overlap with
    // ANY other candidate to guarantee the pixels under their bounds belong exclusively
    // to that shape in Backbuffer.
    let viewport = render_state.viewbox.area;
    let scale = render_state.get_scale();
    let mut candidates: Vec<(Uuid, Rect, Rect)> = Vec::new(); // (id, doc_bounds, selrect)

    let root_ids: Vec<Uuid> = match tree.get(&Uuid::nil()) {
        Some(root) => root.children_ids(false),
        None => Vec::new(),
    };

    for shape_id in root_ids {
        let Some(shape) = tree.get(&shape_id) else {
            continue;
        };
        if shape.hidden {
            continue;
        }

        let doc_bounds = shape.extrect(tree, 1.0);
        if !doc_bounds.intersects(viewport) {
            continue;
        }

        // Also require selrect to be visible; used for drag delta placement.
        let selrect = shape.selrect();
        if !selrect.intersects(viewport) {
            continue;
        }

        candidates.push((shape.id, doc_bounds, selrect));
    }

    // Filter out any candidate that overlaps with any other candidate.
    // Sort by left edge so the inner loop can break early once no further
    // x-overlap is possible, reducing comparisons from O(N²) to O(N log N)
    // in typical layouts where shapes are spread out.
    candidates.sort_unstable_by(|a, b| {
        a.1.left
            .partial_cmp(&b.1.left)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let n = candidates.len();
    let mut is_overlapping = vec![false; n];
    for i in 0..n {
        for j in (i + 1)..n {
            if candidates[j].1.left >= candidates[i].1.right {
                break; // sorted: no further x-overlap possible for i
            }
            if is_overlapping[i] && is_overlapping[j] {
                continue; // both already excluded, skip check
            }
            if candidates[i].1.intersects(candidates[j].1) {
                is_overlapping[i] = true;
                is_overlapping[j] = true;
            }
        }
    }
    let non_overlapping: Vec<(Uuid, Rect, Rect)> = candidates
        .iter()
        .zip(is_overlapping.iter())
        .filter_map(|((id, bounds, selrect), ov)| {
            if !ov {
                Some((*id, *bounds, *selrect))
            } else {
                None
            }
        })
        .collect();

    let vb_left = render_state.viewbox.area.left;
    let vb_top = render_state.viewbox.area.top;
    let (bb_w, bb_h) = render_state.surfaces.surface_size(SurfaceId::Backbuffer);
    let max_snap_px = get_gpu_state().max_texture_size();

    // Snapshot the atlas once for the whole pass so that all shapes sharing
    // the tile/atlas fallback path reuse the same GPU image rather than each
    // triggering a separate `image_snapshot` flush.
    let atlas_snap = render_state.surfaces.atlas.snapshot_for_drag_crop();

    // Scratch surface reused across all shapes that need the tile/atlas
    // fallback — avoids one WebGL texture allocation per shape.
    // Created lazily on first use and grown if a later shape needs more space.
    let mut scratch_surface: Option<skia::Surface> = None;

    for (id, doc_bounds, selrect) in non_overlapping {
        let left = ((doc_bounds.left - vb_left) * scale).floor() as i32;
        let top = ((doc_bounds.top - vb_top) * scale).floor() as i32;
        let right = ((doc_bounds.right - vb_left) * scale).ceil() as i32;
        let bottom = ((doc_bounds.bottom - vb_top) * scale).ceil() as i32;
        if right <= left || bottom <= top {
            continue;
        }
        let src_irect = skia::IRect::new(left, top, right, bottom);

        let src_doc_bounds = Rect::new(
            src_irect.left as f32 / scale + vb_left,
            src_irect.top as f32 / scale + vb_top,
            src_irect.right as f32 / scale + vb_left,
            src_irect.bottom as f32 / scale + vb_top,
        );

        let full_w = src_irect.width();
        let full_h = src_irect.height();
        let (win_ox, win_oy, win_w, win_h) = drag_crop_snapshot_window_px(
            max_snap_px,
            full_w,
            full_h,
            viewport,
            vb_left,
            vb_top,
            scale,
            src_irect.left,
            src_irect.top,
            src_doc_bounds,
        );
        let window_irect = skia::IRect::new(
            src_irect.left + win_ox,
            src_irect.top + win_oy,
            src_irect.left + win_ox + win_w,
            src_irect.top + win_oy + win_h,
        );

        let src_doc_window = Rect::new(
            window_irect.left as f32 / scale + vb_left,
            window_irect.top as f32 / scale + vb_top,
            window_irect.right as f32 / scale + vb_left,
            window_irect.bottom as f32 / scale + vb_top,
        );

        let in_backbuffer = window_irect.left >= 0
            && window_irect.top >= 0
            && window_irect.right <= bb_w
            && window_irect.bottom <= bb_h;

        let backbuffer_snap = if in_backbuffer {
            render_state
                .surfaces
                .snapshot_rect(SurfaceId::Backbuffer, window_irect)
        } else {
            None
        };

        let image = if let Some(img) = backbuffer_snap {
            img
        } else {
            // Ensure the scratch surface is large enough for this window.
            // Grow (reallocate) only when necessary so that the common case
            // of similarly-sized shapes pays zero extra allocation cost.
            let needs_alloc = scratch_surface
                .as_ref()
                .is_none_or(|s| s.width() < win_w || s.height() < win_h);
            if needs_alloc {
                scratch_surface = get_gpu_state()
                    .create_surface_with_isize(
                        "drag_crop_scratch".to_string(),
                        skia::ISize::new(win_w, win_h),
                    )
                    .ok();
            }
            let Some(scratch) = scratch_surface.as_mut() else {
                continue;
            };
            let Some(img) = render_state.surfaces.try_snapshot_doc_rect_from_tiles_and_atlas(
                scratch,
                atlas_snap.as_ref(),
                src_doc_window,
                window_irect,
                win_w,
                win_h,
                vb_left,
                vb_top,
                scale,
            ) else {
                continue;
            };
            img
        };

        render_state.backbuffer_crop_cache.insert(
            id,
            InteractiveDragCrop {
                src_doc_bounds: src_doc_window,
                src_selrect: selrect,
                capture_vb_left: vb_left,
                capture_vb_top: vb_top,
                capture_src_left: window_irect.left,
                capture_src_top: window_irect.top,
                image,
            },
        );
    }
}
