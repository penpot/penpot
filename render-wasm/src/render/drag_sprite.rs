//! Drag-sprite optimization (Phase 3: backdrop + sprite + fast-path render).
//!
//! At gesture start (first `set_modifiers` of an interactive transform):
//! 1. Capture the affected tiles WITHOUT the dragged shape into the
//!    persistent atlas — this is the "scene minus shape" backdrop.
//! 2. Capture the shape itself into a GPU image — the sprite.
//! 3. Snapshot the cumulative `tile_eviction_seq` AFTER both captures.
//!
//! Per rAF during drag:
//! - Check sprite is still valid: `tile_eviction_seq` matches snapshot,
//!   modifier is translation-only, zoom hasn't changed. Mismatch → flip
//!   to `Disabled` for the rest of the gesture (slow path resumes).
//! - Draw the atlas (which holds the backdrop) to the target.
//! - Blit the sprite at the modifier-transformed position.
//!
//! On `set_modifiers_end`: discard sprite and let the normal post-gesture
//! render rebuild the affected tiles with the shape at its committed
//! position.

use std::collections::{HashMap, HashSet};

use skia_safe::{self as skia, Rect};

use super::{ui, NodeRenderState, RenderState, SurfaceId};
use crate::error::Result;
use crate::performance;
use crate::state::ShapesPoolRef;
use crate::tiles::{self, TileRect};
use crate::uuid::Uuid;

/// State of the drag-sprite optimization for the current gesture.
#[derive(Default)]
pub enum DragSpriteState {
    /// No active gesture, or capture not yet attempted.
    #[default]
    Idle,
    /// Sprite captured at gesture start. Per-rAF compositing path is
    /// active subject to per-frame validity checks.
    Captured(DragSprite),
    /// Capture was attempted and is invalid for the rest of this
    /// gesture (preconditions not met, unexpected eviction, non-
    /// translation modifier, zoom change, etc.). Slow path resumes.
    Disabled,
}

/// Pre-rendered raster of the dragged shape, plus metadata used to
/// validate that the cached state is still safe to use each frame.
#[derive(Clone)]
pub struct DragSprite {
    /// GPU-backed snapshot of the shape rendered into Export at the
    /// pre-modifier position, with all fills/strokes/effects baked in.
    pub image: skia::Image,
    /// Shape's pre-drag extrect in doc space. The per-rAF blit uses
    /// `modifier.map_rect(base_doc_rect)` to place the sprite.
    pub base_doc_rect: Rect,
    /// Render scale used when the sprite was captured. Mismatch with
    /// the current zoom invalidates the sprite (would alias).
    pub sprite_scale: f32,
    /// Id of the shape this sprite represents.
    pub shape_id: Uuid,
    /// `RenderState::tile_eviction_seq` AFTER backdrop capture
    /// completes. Any subsequent eviction bumps the live counter,
    /// signalling the backdrop atlas is no longer trustworthy.
    pub captured_eviction_seq: u32,
    /// Viewport-sized capture of the shapes that render *after* the
    /// dragged shape in document order (its z-order successors at every
    /// ancestor level). Drawn on top of the sprite each rAF so shapes
    /// that should overlap the dragged shape do, instead of the sprite
    /// always sitting on top. `None` when the dragged shape is on top
    /// at every level (no above-shapes).
    pub above_image: Option<skia::Image>,
}

/// Find the unique "primary" shape in the modifier set: the one whose
/// ancestors are NOT also in the set. For a layout-container drag
/// `propagate-modifiers` expands the user's single-shape modifier into
/// transforms-per-descendant; this picks the topmost one back out so
/// the sprite path can capture the parent (whose subtree contains all
/// the propagated children).
///
/// Returns `None` when the set has no unique root (multi-shape drag of
/// unrelated shapes), in which case the fast path is disabled.
///
/// Currently dormant — pairs with the layout-drag atlas-sample
/// shortcut that hasn't landed cleanly yet.
#[allow(dead_code)]
fn find_primary_shape(ids: &[Uuid], tree: ShapesPoolRef) -> Option<Uuid> {
    let id_set: HashSet<Uuid> = ids.iter().copied().collect();
    let mut roots: Vec<Uuid> = Vec::new();
    for &id in ids {
        // Walk ancestors; if any is in the set, `id` isn't a root.
        let mut cur = tree.get(&id).and_then(|s| s.parent_id);
        let mut has_ancestor_in_set = false;
        while let Some(pid) = cur {
            if id_set.contains(&pid) {
                has_ancestor_in_set = true;
                break;
            }
            cur = tree.get(&pid).and_then(|s| s.parent_id);
        }
        if !has_ancestor_in_set {
            roots.push(id);
            if roots.len() > 1 {
                return None;
            }
        }
    }
    if roots.len() == 1 {
        Some(roots[0])
    } else {
        None
    }
}

/// True iff every matrix in `modifiers` is a pure translation AND they
/// all share the same `(tx, ty)` (within `eps`). This is the safety
/// invariant for the layout-container drag path: a layout that's just
/// being dragged emits the same translation for the parent and every
/// auto-laid-out descendant. If any descendant differs, the layout has
/// reflowed (e.g. a flex item resized) and the captured sprite would
/// no longer represent the live state.
///
/// Currently dormant — pairs with `find_primary_shape`.
#[allow(dead_code)]
fn all_modifiers_uniform_translation<'a, I>(modifiers: I) -> bool
where
    I: IntoIterator<Item = &'a skia::Matrix>,
{
    let eps = 1e-3_f32;
    let mut first: Option<(f32, f32)> = None;
    for m in modifiers {
        if !is_translation_only(m) {
            return false;
        }
        let (tx, ty) = (m.translate_x(), m.translate_y());
        match first {
            None => first = Some((tx, ty)),
            Some((fx, fy)) => {
                if (tx - fx).abs() > eps || (ty - fy).abs() > eps {
                    return false;
                }
            }
        }
    }
    true
}

/// Returns true iff the matrix is a pure translation (within a small
/// epsilon to absorb floating-point noise from modifier composition).
/// Drag-sprite's per-rAF blit assumes the captured pixels are still
/// correct at the new position — so any rotate/scale/skew/perspective
/// requires falling back to the slow path.
///
/// We can't use `Matrix::is_translate` directly: it consults Skia's
/// type mask which is strict (any scale != exactly 1.0 sets the SCALE
/// bit). In practice modifier matrices arrive with sub-microscale
/// noise like `sx = 0.99999964` from upstream matrix composition,
/// which is visually pure translation but trips the strict check.
fn is_translation_only(m: &skia::Matrix) -> bool {
    let eps = 1e-4_f32;
    (m.scale_x() - 1.0).abs() < eps
        && (m.scale_y() - 1.0).abs() < eps
        && m.skew_x().abs() < eps
        && m.skew_y().abs() < eps
        && m.persp_x().abs() < eps
        && m.persp_y().abs() < eps
}

impl RenderState {
    /// Idempotent. Called from `set_modifiers_start`.
    pub fn drag_sprite_reset(&mut self) {
        self.drag_sprite_state = DragSpriteState::Idle;
    }

    /// Drop any captured sprite. Called from `set_modifiers_end`. The
    /// subsequent commit-time render will rebuild the affected tiles
    /// with the shape at its final position.
    pub fn drag_sprite_clear(&mut self) {
        self.drag_sprite_state = DragSpriteState::Idle;
    }

    /// True iff the drag-sprite fast path is currently in `Captured`
    /// state. Callers in `main.rs` use this to suppress mid-drag
    /// `rebuild_modifier_tiles` (which would wipe the backdrop).
    pub fn drag_sprite_is_active(&self) -> bool {
        matches!(self.drag_sprite_state, DragSpriteState::Captured(_))
    }

    /// Try to capture the dragged shape's sprite + scene-minus-shape
    /// backdrop. No-op if state is not `Idle` (already captured or
    /// disabled). On any error, transitions to `Disabled`.
    ///
    /// Must be called BEFORE `state.set_modifiers(modifiers)` on the
    /// first event of a gesture so the shape is rendered at its
    /// pre-drag position.
    pub fn drag_sprite_try_capture(
        &mut self,
        ids: &[Uuid],
        _modifiers: &HashMap<Uuid, skia::Matrix>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<()> {
        if !matches!(self.drag_sprite_state, DragSpriteState::Idle) {
            return Ok(());
        }
        if ids.len() != 1 {
            // Multi-shape drag (incl. layout containers whose
            // `propagate-modifiers` expanded into per-descendant
            // transforms): no fast path — slow tile walker runs for the
            // gesture. Layout-aware drag-sprite optimization is a known
            // open problem.
            self.drag_sprite_state = DragSpriteState::Disabled;
            return Ok(());
        }
        let shape_id = ids[0];

        match self.capture_drag_sprite_internal(&shape_id, tree, timestamp) {
            Ok(sprite) => {
                self.drag_sprite_state = DragSpriteState::Captured(sprite);
            }
            Err(_) => {
                self.drag_sprite_state = DragSpriteState::Disabled;
            }
        }
        Ok(())
    }

    /// Try the fast-path render. Returns true if the frame was rendered
    /// (caller should skip the normal render loop). Returns false if
    /// preconditions weren't met (state not Captured, eviction mismatch,
    /// non-translation modifier, zoom change), in which case the slow
    /// path runs.
    ///
    /// Side effect: on validity failure, transitions state to `Disabled`
    /// so the slow path runs for the rest of the gesture.
    pub fn drag_sprite_try_render_frame(&mut self, tree: ShapesPoolRef) -> bool {
        // Borrow-check dance: extract sprite by clone (skia::Image is
        // Arc-like, so this is cheap), then we're free to mutate self.
        let sprite = match &self.drag_sprite_state {
            DragSpriteState::Captured(s) => s.clone(),
            _ => return false,
        };

        // Validity check 1: eviction-sequence must match.
        if sprite.captured_eviction_seq != self.tile_eviction_seq {
            crate::run_script!(format!(
                "console.log('[drag_sprite] disabled: eviction_seq mismatch (captured={} current={})')",
                sprite.captured_eviction_seq, self.tile_eviction_seq
            ));
            self.drag_sprite_state = DragSpriteState::Disabled;
            return false;
        }

        // Validity check 2: zoom must match.
        if (sprite.sprite_scale - self.get_scale()).abs() > 1e-5 {
            crate::run_script!(format!(
                "console.log('[drag_sprite] disabled: scale changed ({} -> {})')",
                sprite.sprite_scale,
                self.get_scale()
            ));
            self.drag_sprite_state = DragSpriteState::Disabled;
            return false;
        }

        // Validity check 3: modifier must be translation-only.
        let modifier = tree.get_modifier(&sprite.shape_id);
        if let Some(m) = modifier {
            if !is_translation_only(m) {
                crate::run_script!(format!(
                    "console.log('[drag_sprite] disabled: modifier not translation-only: sx={} sy={} kx={} ky={} tx={} ty={} px={} py={}')",
                    m.scale_x(),
                    m.scale_y(),
                    m.skew_x(),
                    m.skew_y(),
                    m.translate_x(),
                    m.translate_y(),
                    m.persp_x(),
                    m.persp_y()
                ));
                self.drag_sprite_state = DragSpriteState::Disabled;
                return false;
            }
        }

        self.render_drag_sprite_frame_inner(&sprite, tree);
        true
    }

    /// Bump the cumulative eviction counter. Called from
    /// `remove_cached_tile`. The drag-sprite fast path uses this to
    /// detect mid-gesture invalidation.
    pub fn drag_sprite_notify_eviction(&mut self) {
        self.tile_eviction_seq = self.tile_eviction_seq.wrapping_add(1);
    }

    /// Capture phase: backdrop + sprite. Mirrors commit `667b503505`'s
    /// `capture_drag_sprite` but with the safety-net `eviction_seq`
    /// snapshot taken AFTER both phases complete.
    fn capture_drag_sprite_internal(
        &mut self,
        id: &Uuid,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<DragSprite> {
        performance::begin_measure!("drag_sprite_capture");
        let dx_t = crate::get_now!();

        let scale = self.get_scale();
        let Some(shape) = tree.get(id) else {
            performance::end_measure!("drag_sprite_capture");
            return Err(crate::error::Error::CriticalError(format!(
                "drag_sprite: shape {} not found",
                id
            )));
        };
        let base_doc_rect = shape.extrect(tree, scale);
        if base_doc_rect.is_empty() {
            performance::end_measure!("drag_sprite_capture");
            return Err(crate::error::Error::CriticalError(
                "drag_sprite: empty extrect".to_string(),
            ));
        }

        // Phase 1: backdrop. Re-renders affected tiles with the dragged
        // subtree excluded; result lands in the persistent atlas.
        let backdrop_result = self.capture_drag_backdrop(id, tree, timestamp, base_doc_rect);

        // Phase 2: sprite. Mirrors `render_shape_pixels` save/restore
        // but returns a Skia Image instead of PNG bytes.
        let target_surface = SurfaceId::Export;

        let saved_focus_mode = self.focus_mode.clone();
        let saved_export_context = self.export_context;
        let saved_render_area = self.render_area;
        let saved_render_area_with_margins = self.render_area_with_margins;
        let saved_current_tile = self.current_tile;
        let saved_pending_nodes = std::mem::take(&mut self.pending_nodes);
        let saved_nested_fills = std::mem::take(&mut self.nested_fills);
        let saved_nested_blurs = std::mem::take(&mut self.nested_blurs);
        let saved_nested_shadows = std::mem::take(&mut self.nested_shadows);
        let saved_ignore_nested_blurs = self.ignore_nested_blurs;
        let saved_preview_mode = self.preview_mode;

        self.focus_mode.clear();
        self.surfaces
            .canvas(target_surface)
            .clear(skia::Color::TRANSPARENT);

        let mut render_rect = base_doc_rect;
        self.export_context = Some((render_rect, scale));
        let margins = self.surfaces.margins;
        render_rect.offset((margins.width as f32 / scale, margins.height as f32 / scale));

        self.surfaces.resize_export_surface(scale, render_rect);
        self.render_area = render_rect;
        self.render_area_with_margins = render_rect;
        self.surfaces.update_render_context(render_rect, scale);

        self.pending_nodes.push(NodeRenderState {
            id: *id,
            visited_children: false,
            clip_bounds: None,
            visited_mask: false,
            mask: false,
            flattened: false,
        });
        let sprite_result = self.render_shape_tree_partial_uncached(tree, timestamp, false, true);

        self.export_context = None;
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, target_surface);
        let image = self.surfaces.snapshot(target_surface);

        // Restore workspace state.
        self.focus_mode = saved_focus_mode;
        self.export_context = saved_export_context;
        self.render_area = saved_render_area;
        self.render_area_with_margins = saved_render_area_with_margins;
        self.current_tile = saved_current_tile;
        self.pending_nodes = saved_pending_nodes;
        self.nested_fills = saved_nested_fills;
        self.nested_blurs = saved_nested_blurs;
        self.nested_shadows = saved_nested_shadows;
        self.ignore_nested_blurs = saved_ignore_nested_blurs;
        self.preview_mode = saved_preview_mode;

        let workspace_scale = self.get_scale();
        if let Some(tile) = self.current_tile {
            self.update_render_context(tile);
        } else if !self.render_area.is_empty() {
            self.surfaces
                .update_render_context(self.render_area, workspace_scale);
        }

        // Surface errors AFTER the state restore above so a mid-render
        // failure doesn't strand the renderer.
        backdrop_result?;
        sprite_result?;

        // Phase 3: above-shapes overlay. Render the shapes that come
        // AFTER the dragged shape in document order (z-order successors
        // at every ancestor level) into a viewport-sized image. Per-rAF
        // we blit it on top of the sprite so shapes meant to be above
        // the dragged shape actually appear above it. `None` when the
        // dragged shape is on top at every level.
        let above_image = self.capture_drag_above(id, tree, timestamp).unwrap_or(None);

        // Snapshot eviction seq AFTER all captures (each capture phase
        // can evict tiles; we want to detect *additional* evictions
        // that fire mid-gesture).
        let captured_eviction_seq = self.tile_eviction_seq;

        let dx_dt = crate::get_now!() - dx_t;
        crate::run_script!(format!(
            "console.log('[drag_sprite] captured shape={} extrect_w={:.0} extrect_h={:.0} scale={:.2} above={} took={:.1}ms')",
            id,
            base_doc_rect.width(),
            base_doc_rect.height(),
            scale,
            above_image.is_some(),
            dx_dt
        ));

        performance::end_measure!("drag_sprite_capture");

        Ok(DragSprite {
            image,
            base_doc_rect,
            sprite_scale: scale,
            shape_id: *id,
            captured_eviction_seq,
            above_image,
        })
    }

    /// Walk from the dragged shape up to the root, collecting siblings
    /// that come *before* the current node in `children_ids` at each
    /// level — those are the shapes drawn ON TOP of it (z-order
    /// successors). The returned list is the push-order for the
    /// renderer's pending_nodes stack: outermost ancestor's above-
    /// siblings come first so they pop last and render last (on top).
    fn collect_above_shapes_push_order(&self, dragged_id: &Uuid, tree: ShapesPoolRef) -> Vec<Uuid> {
        let mut levels: Vec<Vec<Uuid>> = Vec::new();
        let mut current = *dragged_id;
        loop {
            let parent_id = tree
                .get(&current)
                .and_then(|s| s.parent_id)
                .unwrap_or(Uuid::nil());
            let Some(parent) = tree.get(&parent_id) else {
                break;
            };
            let siblings = parent.children_ids(false);
            if let Some(idx) = siblings.iter().position(|id| *id == current) {
                // children_ids[..idx] are the siblings rendered AFTER
                // `current` in document order. children_ids[0] is the
                // top-most of those.
                levels.push(siblings[..idx].to_vec());
            }
            if parent_id == Uuid::nil() {
                break;
            }
            current = parent_id;
        }
        // Outer levels (highest z) first → push them first → they pop
        // last → render last → on top.
        let mut result = Vec::new();
        for level in levels.iter().rev() {
            result.extend_from_slice(level);
        }
        result
    }

    /// Render the above-set onto a viewport-sized image. Mirrors the
    /// `render_shape_pixels` save/restore pattern but with multiple
    /// shapes pushed onto `pending_nodes` and a viewport-scoped render
    /// area. Returns `None` when there are no above-shapes (drag is on
    /// top of the stack everywhere).
    fn capture_drag_above(
        &mut self,
        dragged_id: &Uuid,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<Option<skia::Image>> {
        let above_ids = self.collect_above_shapes_push_order(dragged_id, tree);
        if above_ids.is_empty() {
            return Ok(None);
        }

        let target_surface = SurfaceId::Export;
        let scale = self.get_scale();

        // Save state we're about to mutate.
        let saved_focus_mode = self.focus_mode.clone();
        let saved_export_context = self.export_context;
        let saved_render_area = self.render_area;
        let saved_render_area_with_margins = self.render_area_with_margins;
        let saved_current_tile = self.current_tile;
        let saved_pending_nodes = std::mem::take(&mut self.pending_nodes);
        let saved_nested_fills = std::mem::take(&mut self.nested_fills);
        let saved_nested_blurs = std::mem::take(&mut self.nested_blurs);
        let saved_nested_shadows = std::mem::take(&mut self.nested_shadows);
        let saved_ignore_nested_blurs = self.ignore_nested_blurs;
        let saved_preview_mode = self.preview_mode;

        self.focus_mode.clear();
        self.surfaces
            .canvas(target_surface)
            .clear(skia::Color::TRANSPARENT);

        // Size Export to the workspace viewport (in doc space) plus the
        // standard margin band — same convention as the sprite capture,
        // so the per-rAF blit reuses the same translation math.
        let viewport_doc = self.viewbox.area;
        let mut render_rect = viewport_doc;
        self.export_context = Some((render_rect, scale));
        let margins = self.surfaces.margins;
        render_rect.offset((margins.width as f32 / scale, margins.height as f32 / scale));
        self.surfaces.resize_export_surface(scale, render_rect);
        self.render_area = render_rect;
        self.render_area_with_margins = render_rect;
        self.surfaces.update_render_context(render_rect, scale);

        for id in &above_ids {
            self.pending_nodes.push(NodeRenderState {
                id: *id,
                visited_children: false,
                clip_bounds: None,
                visited_mask: false,
                mask: false,
                flattened: false,
            });
        }
        let render_result = self.render_shape_tree_partial_uncached(tree, timestamp, false, true);

        self.export_context = None;
        self.surfaces
            .flush_and_submit(&mut self.gpu_state, target_surface);
        let image = self.surfaces.snapshot(target_surface);

        // Restore workspace state.
        self.focus_mode = saved_focus_mode;
        self.export_context = saved_export_context;
        self.render_area = saved_render_area;
        self.render_area_with_margins = saved_render_area_with_margins;
        self.current_tile = saved_current_tile;
        self.pending_nodes = saved_pending_nodes;
        self.nested_fills = saved_nested_fills;
        self.nested_blurs = saved_nested_blurs;
        self.nested_shadows = saved_nested_shadows;
        self.ignore_nested_blurs = saved_ignore_nested_blurs;
        self.preview_mode = saved_preview_mode;

        let workspace_scale = self.get_scale();
        if let Some(tile) = self.current_tile {
            self.update_render_context(tile);
        } else if !self.render_area.is_empty() {
            self.surfaces
                .update_render_context(self.render_area, workspace_scale);
        }

        render_result?;
        Ok(Some(image))
    }

    /// Compute the set of interest-area tiles covering a doc-space rect.
    fn tiles_covering_doc_rect(&self, doc_rect: skia::Rect) -> HashSet<tiles::Tile> {
        let scale = self.get_scale();
        let tile_size = tiles::get_tile_size(scale);
        let TileRect(x1, y1, x2, y2) = tiles::get_tiles_for_rect(doc_rect, tile_size);
        let mut out = HashSet::new();
        for tx in x1..=x2 {
            for ty in y1..=y2 {
                let tile = tiles::Tile::from(tx, ty);
                if self.tile_viewbox.interest_rect.contains(&tile) {
                    out.insert(tile);
                }
            }
        }
        out
    }

    /// Re-render the tiles covering the dragged shape's pre-drag bounds
    /// with that subtree excluded, leaving the atlas with pixel-correct
    /// "scene minus shape" content. Texture cache for the affected tiles
    /// is invalidated so the post-gesture render recomputes them with
    /// the shape at its final position.
    fn capture_drag_backdrop(
        &mut self,
        shape_id: &Uuid,
        tree: ShapesPoolRef,
        timestamp: i32,
        base_doc_rect: skia::Rect,
    ) -> Result<()> {
        performance::begin_measure!("drag_sprite_backdrop");

        let Some(shape) = tree.get(shape_id) else {
            performance::end_measure!("drag_sprite_backdrop");
            return Ok(());
        };
        let mut exclude: HashSet<Uuid> = HashSet::new();
        exclude.insert(*shape_id);
        for child_id in shape.all_children_iter(tree, true, true) {
            exclude.insert(child_id);
        }

        let capture_tiles = self.tiles_covering_doc_rect(base_doc_rect);
        if capture_tiles.is_empty() {
            performance::end_measure!("drag_sprite_backdrop");
            return Ok(());
        }

        // Clear the atlas region under the dragged shape so the nested
        // render's tile blits write into a blank area. Without this,
        // SrcOver compositing could leak old shape pixels through the
        // transparent gaps.
        let _ = self
            .surfaces
            .clear_doc_rect_in_atlas(&mut self.gpu_state, base_doc_rect);

        // Evict any cached tile textures for the affected tiles so the
        // nested render is not short-circuited by the cache fast path.
        for tile in &capture_tiles {
            self.surfaces
                .remove_cached_tile_surface(&mut self.gpu_state, *tile);
        }

        // Save state the nested render mutates.
        let saved_pending_tiles = std::mem::take(&mut self.pending_tiles.list);
        let saved_pending_nodes = std::mem::take(&mut self.pending_nodes);
        let saved_current_tile = self.current_tile;
        let saved_render_in_progress = self.render_in_progress;

        self.pending_tiles.list = capture_tiles.iter().copied().collect();
        self.current_tile = None;
        self.render_in_progress = true;
        self.drag_capture_exclude = Some(exclude);
        self.surfaces
            .canvas(SurfaceId::Current)
            .clear(self.background_color);

        let result = self.render_shape_tree_partial(None, tree, timestamp, false);

        // Restore.
        self.drag_capture_exclude = None;
        self.pending_tiles.list = saved_pending_tiles;
        self.pending_nodes = saved_pending_nodes;
        self.current_tile = saved_current_tile;
        self.render_in_progress = saved_render_in_progress;
        self.surfaces
            .canvas(SurfaceId::Current)
            .clear(skia::Color::TRANSPARENT);

        result?;

        // Atlas now holds scene-minus-shape pixels for `capture_tiles`.
        // Drop the tile textures so the post-gesture render recomputes
        // them (the cached textures also lack the shape — same wrong
        // content, just baked into the texture cache).
        for tile in &capture_tiles {
            self.surfaces.invalidate_tile_texture_only(*tile);
        }

        performance::end_measure!("drag_sprite_backdrop");
        Ok(())
    }

    /// Per-rAF fast path: draw atlas as scene backdrop, then blit the
    /// pre-rendered sprite at the modifier-transformed position.
    fn render_drag_sprite_frame_inner(&mut self, sprite: &DragSprite, tree: ShapesPoolRef) {
        performance::begin_measure!("drag_sprite_render_frame");

        // Apply the (validated translation-only) modifier to the
        // sprite's pre-drag rect.
        let dst_doc_rect = if let Some(m) = tree.get_modifier(&sprite.shape_id) {
            m.map_rect(sprite.base_doc_rect).0
        } else {
            sprite.base_doc_rect
        };

        // Draw the atlas (which holds the backdrop) to Target.
        if self.surfaces.has_atlas() {
            self.surfaces.draw_atlas_to_target(
                self.viewbox,
                self.options.dpr(),
                self.background_color,
            );
        } else {
            self.surfaces
                .canvas(SurfaceId::Target)
                .clear(self.background_color);
        }

        // Blit the sprite at the new position, in target pixel space.
        let s = self.viewbox.zoom * self.options.dpr();
        let dst = skia::Rect::from_xywh(
            (dst_doc_rect.left + self.viewbox.pan_x) * s,
            (dst_doc_rect.top + self.viewbox.pan_y) * s,
            dst_doc_rect.width() * s,
            dst_doc_rect.height() * s,
        );

        let canvas = self.surfaces.canvas(SurfaceId::Target);
        canvas.save();
        canvas.reset_matrix();
        canvas.draw_image_rect(&sprite.image, None, dst, &skia::Paint::default());
        canvas.restore();

        // Above-shapes overlay: shapes drawn AFTER the dragged shape in
        // document order go on top of the sprite, restoring correct
        // z-order when they overlap. The above_image was rendered with
        // canvas pixel (0,0) mapped to the viewport's top-left in doc
        // space — exactly the same mapping as Target — so blit at (0,0).
        if let Some(above) = sprite.above_image.as_ref() {
            let canvas = self.surfaces.canvas(SurfaceId::Target);
            canvas.save();
            canvas.reset_matrix();
            canvas.draw_image(above, (0, 0), Some(&skia::Paint::default()));
            canvas.restore();
        }

        // Selection handles, snap guides, etc. ride on top.
        ui::render(self, tree);

        self.flush_and_submit();
        performance::end_measure!("drag_sprite_render_frame");
    }
}
