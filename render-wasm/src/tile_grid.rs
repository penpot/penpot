//! Dependency-aware tile scheduler for the render-wasm renderer.
//!
//! Replaces `PendingTiles`, `pending_nodes`, and `TileHashMap` with a single
//! data structure that pre-computes a topologically sorted, flat render schedule.
//! Tiles containing gather effects (glass, background blur) render after their
//! dependency tiles, so the accumulated Target surface provides a correct
//! cross-tile backdrop.

#![cfg(feature = "tile-scheduler")]

use std::collections::{BinaryHeap, HashMap, HashSet};

use skia_safe as skia;

use crate::error::{Error, Result};
use crate::performance;
use crate::render::{RenderState, SurfaceId};
use crate::shapes::{BlurType, Shape, Type};
use crate::state::{ShapesPoolMutRef, ShapesPoolRef};
use crate::tiles::{self, Tile, TileRect, TileViewbox};
use crate::uuid::Uuid;
use crate::view::Viewbox;
use crate::wapi;


// ── Data structures ─────────────────────────────────────────────────────

/// Per-shape metadata stored inline in the spatial index.
/// Only derived data — the full shape is always read from ShapesPool.
#[derive(Debug, Clone)]
pub struct ShapeEntry {
    pub id: Uuid,
    pub z_index: i32,
    pub has_gather: bool,
}

/// A single step in the pre-computed render schedule.
#[derive(Debug, Clone)]
pub enum RenderStep {
    /// Switch to rendering a new tile.
    SetTile(Tile),
    /// Enter a container (Frame/Group): save_layer, clip, transform.
    Enter(Uuid),
    /// Render a shape: fills, strokes, shadows, glass, etc.
    Render(Uuid),
    /// Exit a container: restore layer.
    Exit(Uuid),
}

/// Entry in the priority queue for Kahn's algorithm.
/// Lower priority value = renders first.
#[derive(Eq, PartialEq)]
struct TilePriority {
    tile: Tile,
    /// Priority group: 0 = visible+cached, 1 = visible+uncached,
    /// 2 = interest+cached, 3 = interest+uncached
    group: u8,
    /// Position in the spiral (lower = closer to center)
    spiral_index: usize,
}

impl Ord for TilePriority {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        // BinaryHeap is a max-heap, so reverse the ordering:
        // we want lowest group first, then lowest spiral_index
        other
            .group
            .cmp(&self.group)
            .then(other.spiral_index.cmp(&self.spiral_index))
    }
}

impl PartialOrd for TilePriority {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// Spatial index + topological scheduler.
/// Rebuilt on viewport/shape changes. The renderer just iterates the schedule.
pub struct TileGrid {
    // ── Spatial index ────────────────────────────────────────────────
    /// Tile → shapes on it, sorted by z-index.
    grid: HashMap<Tile, Vec<ShapeEntry>>,
    /// Shape → tiles it occupies.
    index: HashMap<Uuid, HashSet<Tile>>,

    // ── Render schedule ──────────────────────────────────────────────
    /// Pre-computed flat render schedule. Index 0 executes first.
    schedule: Vec<RenderStep>,
    /// Current position for yield/resume across animation frames.
    cursor: usize,
}

impl TileGrid {
    pub fn new() -> Self {
        TileGrid {
            grid: HashMap::new(),
            index: HashMap::new(),
            schedule: Vec::new(),
            cursor: 0,
        }
    }

    // ── Spatial index operations ─────────────────────────────────────

    pub fn get_shapes_at(&self, tile: Tile) -> Option<&[ShapeEntry]> {
        self.grid.get(&tile).map(|v| v.as_slice())
    }

    pub fn get_tiles_of(&self, shape_id: &Uuid) -> Option<&HashSet<Tile>> {
        self.index.get(shape_id)
    }

    pub fn add_shape_at(&mut self, tile: Tile, entry: ShapeEntry) {
        let id = entry.id;
        let entries = self.grid.entry(tile).or_default();
        // Insert in z-sorted position
        let pos = entries
            .binary_search_by(|e| e.z_index.cmp(&entry.z_index))
            .unwrap_or_else(|p| p);
        entries.insert(pos, entry);

        self.index.entry(id).or_default().insert(tile);
    }

    pub fn remove_shape_at(&mut self, tile: Tile, id: Uuid) {
        if let Some(entries) = self.grid.get_mut(&tile) {
            entries.retain(|e| e.id != id);
        }
        if let Some(tiles) = self.index.get_mut(&id) {
            tiles.remove(&tile);
        }
    }

    pub fn invalidate(&mut self) {
        self.grid.clear();
        self.index.clear();
        self.schedule.clear();
        self.cursor = 0;
    }

    // ── Schedule control ────────────────────────────────────────────

    pub fn reset(&mut self) {
        self.cursor = 0;
    }

    pub fn next(&mut self) -> Option<RenderStep> {
        if self.cursor < self.schedule.len() {
            let step = self.schedule[self.cursor].clone();
            self.cursor += 1;
            Some(step)
        } else {
            None
        }
    }

    /// Skip forward to the next SetTile step (used when current tile is cached).
    pub fn skip_to_next_tile(&mut self) {
        while self.cursor < self.schedule.len() {
            if matches!(self.schedule[self.cursor], RenderStep::SetTile(_)) {
                break;
            }
            self.cursor += 1;
        }
    }

    // ── Full rebuild ────────────────────────────────────────────────

    /// Rebuild the full spatial index and render schedule from the shape tree.
    ///
    /// Steps:
    /// 1. Walk shape tree, assign shapes to tiles
    /// 2. Generate spiral from viewport
    /// 3. Scan for gather effects → build dependency edges
    /// 4. Topological sort with spiral priority tiebreaker
    /// 5. Flatten into Vec<RenderStep>
    pub fn rebuild(
        &mut self,
        tree: ShapesPoolRef,
        tile_viewbox: &TileViewbox,
        scale: f32,
    ) {
        performance::begin_measure!("tile_grid_rebuild");

        self.grid.clear();
        self.index.clear();
        self.schedule.clear();
        self.cursor = 0;

        let tile_size = tiles::get_tile_size(scale);
        let interest_rect = &tile_viewbox.interest_rect;

        // Step 1: Walk shape tree, assign shapes to tiles
        let root_id = Uuid::nil();
        if let Some(root) = tree.get(&root_id) {
            let root_children = root.children_ids(false);
            for child_id in root_children.iter() {
                self.index_shape_recursive(*child_id, tree, tile_size, interest_rect, scale);
            }
        }

        // Step 2: Generate spiral
        let spiral = Self::generate_spiral(interest_rect);

        // Step 3: Build dependency graph from gather effects
        let deps = self.build_dependency_graph(tree, tile_size, interest_rect, scale);

        // Step 4: Topological sort with priority
        let sorted_tiles = self.topological_sort(
            &spiral,
            &deps,
            tile_viewbox,
        );

        // Step 5: Flatten into render schedule
        self.build_schedule(&sorted_tiles, tree, tile_size, scale);

        performance::end_measure!("tile_grid_rebuild");
    }

    /// Incremental update: only re-index touched shapes and rebuild schedule.
    pub fn update_touched(
        &mut self,
        touched: &HashSet<Uuid>,
        tree: ShapesPoolRef,
        tile_viewbox: &TileViewbox,
        scale: f32,
    ) -> HashSet<Tile> {
        let tile_size = tiles::get_tile_size(scale);
        let interest_rect = &tile_viewbox.interest_rect;
        let mut affected_tiles = HashSet::new();

        for &id in touched {
            // Remove from old tiles
            if let Some(old_tiles) = self.index.remove(&id) {
                for tile in &old_tiles {
                    if let Some(entries) = self.grid.get_mut(tile) {
                        entries.retain(|e| e.id != id);
                    }
                    affected_tiles.insert(*tile);
                }
            }

            // Re-add if shape still exists
            if let Some(shape) = tree.get(&id) {
                if id != Uuid::nil() {
                    let has_gather = Self::shape_has_gather(shape);
                    let z_index = shape.z_index();
                    let extrect = shape.extrect(tree, scale);
                    let shape_tiles = tiles::get_tiles_for_rect(extrect, tile_size);

                    // Intersect with interest area
                    let ix1 = shape_tiles.x1().max(interest_rect.x1());
                    let iy1 = shape_tiles.y1().max(interest_rect.y1());
                    let ix2 = shape_tiles.x2().min(interest_rect.x2());
                    let iy2 = shape_tiles.y2().min(interest_rect.y2());

                    if ix1 <= ix2 && iy1 <= iy2 {
                        for tx in ix1..=ix2 {
                            for ty in iy1..=iy2 {
                                let tile = Tile::from(tx, ty);
                                self.add_shape_at(
                                    tile,
                                    ShapeEntry {
                                        id,
                                        z_index,
                                        has_gather,
                                    },
                                );
                                affected_tiles.insert(tile);
                            }
                        }
                    }
                }
            }
        }

        // Rebuild schedule with updated index
        let spiral = Self::generate_spiral(interest_rect);
        let deps = self.build_dependency_graph(tree, tile_size, interest_rect, scale);
        let sorted_tiles = self.topological_sort(&spiral, &deps, tile_viewbox);
        self.build_schedule(&sorted_tiles, tree, tile_size, scale);

        affected_tiles
    }

    // ── Internal helpers ────────────────────────────────────────────

    /// Check if a shape has a gather effect (glass or background blur).
    fn shape_has_gather(shape: &Shape) -> bool {
        let has_glass = shape
            .glass
            .as_ref()
            .is_some_and(|g| !g.hidden);

        let has_bg_blur = shape
            .blur
            .is_some_and(|b| !b.hidden && b.blur_type == BlurType::BackgroundBlur);

        has_glass || has_bg_blur
    }

    /// Recursively walk the shape tree and add shapes to the spatial index.
    fn index_shape_recursive(
        &mut self,
        shape_id: Uuid,
        tree: ShapesPoolRef,
        tile_size: f32,
        interest_rect: &TileRect,
        scale: f32,
    ) {
        let Some(shape) = tree.get(&shape_id) else {
            return;
        };

        if shape.hidden {
            return;
        }

        let has_gather = Self::shape_has_gather(shape);
        let z_index = shape.z_index();
        let extrect = shape.extrect(tree, scale);
        let shape_tiles = tiles::get_tiles_for_rect(extrect, tile_size);

        // Intersect with interest area
        let ix1 = shape_tiles.x1().max(interest_rect.x1());
        let iy1 = shape_tiles.y1().max(interest_rect.y1());
        let ix2 = shape_tiles.x2().min(interest_rect.x2());
        let iy2 = shape_tiles.y2().min(interest_rect.y2());

        if ix1 <= ix2 && iy1 <= iy2 {
            for tx in ix1..=ix2 {
                for ty in iy1..=iy2 {
                    let tile = Tile::from(tx, ty);
                    self.add_shape_at(
                        tile,
                        ShapeEntry {
                            id: shape_id,
                            z_index,
                            has_gather,
                        },
                    );
                }
            }
        }

        // Recurse into children
        if shape.is_recursive() {
            for child_id in shape.children_ids_iter(false) {
                self.index_shape_recursive(*child_id, tree, tile_size, interest_rect, scale);
            }
        }
    }

    /// Build dependency graph: for each tile with a gather shape, record which
    /// other tiles must render first (tiles in the gather's sample region that
    /// have shapes below the gather in z-order).
    fn build_dependency_graph(
        &self,
        tree: ShapesPoolRef,
        tile_size: f32,
        interest_rect: &TileRect,
        scale: f32,
    ) -> HashMap<Tile, HashSet<Tile>> {
        let mut deps: HashMap<Tile, HashSet<Tile>> = HashMap::new();

        for (tile, entries) in &self.grid {
            for entry in entries {
                if !entry.has_gather {
                    continue;
                }

                let Some(shape) = tree.get(&entry.id) else {
                    continue;
                };

                // Compute the expanded sample region for this gather effect
                let sample_rect = self.compute_gather_sample_rect(shape, tree, scale);
                let sample_tiles = tiles::get_tiles_for_rect(sample_rect, tile_size);

                // Intersect with interest area
                let sx1 = sample_tiles.x1().max(interest_rect.x1());
                let sy1 = sample_tiles.y1().max(interest_rect.y1());
                let sx2 = sample_tiles.x2().min(interest_rect.x2());
                let sy2 = sample_tiles.y2().min(interest_rect.y2());

                // All tiles in the sample region that contain shapes below
                // this gather in z-order are dependencies
                for stx in sx1..=sx2 {
                    for sty in sy1..=sy2 {
                        let dep_tile = Tile::from(stx, sty);
                        if dep_tile == *tile {
                            continue; // don't depend on self
                        }

                        // Check if this tile has any shapes below the gather's z
                        if let Some(dep_entries) = self.grid.get(&dep_tile) {
                            let has_shapes_below = dep_entries
                                .iter()
                                .any(|e| e.z_index < entry.z_index);
                            if has_shapes_below {
                                deps.entry(*tile).or_default().insert(dep_tile);
                            }
                        }
                    }
                }
            }
        }

        deps
    }

    /// Compute the world-space rectangle a gather effect samples from.
    /// This is the shape bounds expanded by displacement + blur + frost radius.
    fn compute_gather_sample_rect(
        &self,
        shape: &Shape,
        tree: ShapesPoolRef,
        scale: f32,
    ) -> skia::Rect {
        let base = shape.extrect(tree, scale);
        let mut expand = 0.0_f32;

        // Glass effect expansion
        if let Some(glass) = &shape.glass {
            if !glass.hidden {
                // Max displacement from refraction
                let displacement = glass.glass_thickness * glass.refractive_index * 50.0;
                // Blur kernel radius
                let blur_radius = glass.total_blur_sigma() * 3.0 * scale;
                // Frost scatter radius
                let frost_radius = glass.frost * 6.0;
                expand = expand.max(displacement + blur_radius + frost_radius);
            }
        }

        // Background blur expansion
        if let Some(blur) = &shape.blur {
            if !blur.hidden && blur.blur_type == BlurType::BackgroundBlur {
                let blur_radius = blur.value * 3.0 * scale;
                expand = expand.max(blur_radius);
            }
        }

        skia::Rect::from_ltrb(
            base.left - expand,
            base.top - expand,
            base.right + expand,
            base.bottom + expand,
        )
    }

    /// Generate tiles in spiral order from center of rect.
    /// Adapted from PendingTiles::generate_spiral in tiles.rs.
    fn generate_spiral(rect: &TileRect) -> Vec<Tile> {
        let columns = rect.width();
        let rows = rect.height();
        let total = columns * rows;

        if total <= 0 {
            return Vec::new();
        }

        let mut result = Vec::with_capacity(total as usize);
        let mut cx = rect.center_x();
        let mut cy = rect.center_y();

        let ratio = (columns as f32 / rows as f32).ceil() as i32;

        let mut direction_current = 0;
        let mut direction_total_x = ratio;
        let mut direction_total_y = 1;
        let mut direction = 0;
        let mut current = 0;

        result.push(Tile::from(cx, cy));
        while current < total {
            match direction {
                0 => cx += 1,
                1 => cy += 1,
                2 => cx -= 1,
                3 => cy -= 1,
                _ => unreachable!("Invalid direction"),
            }

            result.push(Tile::from(cx, cy));

            direction_current += 1;
            let direction_total = if direction % 2 == 0 {
                direction_total_x
            } else {
                direction_total_y
            };

            if direction_current == direction_total {
                if direction % 2 == 0 {
                    direction_total_x += 1;
                } else {
                    direction_total_y += 1;
                }
                direction = (direction + 1) % 4;
                direction_current = 0;
            }
            current += 1;
        }
        result
    }

    /// Topological sort using Kahn's algorithm with spiral priority as tiebreaker.
    fn topological_sort(
        &self,
        spiral: &[Tile],
        deps: &HashMap<Tile, HashSet<Tile>>,
        tile_viewbox: &TileViewbox,
    ) -> Vec<Tile> {
        // Build spiral index for priority
        let spiral_index: HashMap<Tile, usize> = spiral
            .iter()
            .enumerate()
            .map(|(i, t)| (*t, i))
            .collect();

        // Compute in-degree for each tile
        let mut in_degree: HashMap<Tile, usize> = HashMap::new();
        let mut reverse_deps: HashMap<Tile, Vec<Tile>> = HashMap::new();

        for (tile, dep_set) in deps {
            // Only count dependencies that are in our spiral set
            let count = dep_set
                .iter()
                .filter(|d| spiral_index.contains_key(d))
                .count();
            *in_degree.entry(*tile).or_default() = count;

            for dep in dep_set {
                if spiral_index.contains_key(dep) {
                    reverse_deps.entry(*dep).or_default().push(*tile);
                }
            }
        }

        // Initialize priority queue with tiles that have no dependencies
        let mut ready = BinaryHeap::new();
        for tile in spiral {
            let degree = in_degree.get(tile).copied().unwrap_or(0);
            if degree == 0 {
                let is_visible = tile_viewbox.visible_rect.contains(tile);
                let group = if is_visible { 0 } else { 1 };
                ready.push(TilePriority {
                    tile: *tile,
                    group,
                    spiral_index: spiral_index[tile],
                });
            }
        }

        let mut sorted = Vec::with_capacity(spiral.len());

        while let Some(tp) = ready.pop() {
            sorted.push(tp.tile);

            // Unblock dependents
            if let Some(dependents) = reverse_deps.get(&tp.tile) {
                for dependent in dependents {
                    if let Some(degree) = in_degree.get_mut(dependent) {
                        *degree -= 1;
                        if *degree == 0 {
                            let is_visible = tile_viewbox.visible_rect.contains(dependent);
                            let group = if is_visible { 0 } else { 1 };
                            ready.push(TilePriority {
                                tile: *dependent,
                                group,
                                spiral_index: *spiral_index.get(dependent).unwrap_or(&usize::MAX),
                            });
                        }
                    }
                }
            }
        }

        sorted
    }

    /// Flatten the sorted tile list into a Vec<RenderStep>.
    /// For each tile, emits SetTile + the depth-first shape traversal.
    fn build_schedule(
        &mut self,
        sorted_tiles: &[Tile],
        tree: ShapesPoolRef,
        tile_size: f32,
        scale: f32,
    ) {
        self.schedule.clear();

        // Get root children in paint order (bottom-first).
        // children_ids() returns reversed (topmost first), so reverse back.
        let mut root_children = if let Some(root) = tree.get(&Uuid::nil()) {
            root.children_ids(false)
        } else {
            return;
        };
        root_children.reverse();

        for &tile in sorted_tiles {
            self.schedule.push(RenderStep::SetTile(tile));

            // Get shapes on this tile
            let tile_shapes: HashSet<Uuid> = self
                .grid
                .get(&tile)
                .map(|entries| entries.iter().map(|e| e.id).collect())
                .unwrap_or_default();

            if tile_shapes.is_empty() {
                continue;
            }

            // Check if this tile has gather effects — if so, render all root shapes
            // (same as current needs_full_scene behavior)
            let has_gather = self
                .grid
                .get(&tile)
                .is_some_and(|entries| entries.iter().any(|e| e.has_gather));

            // Emit render steps for shapes on this tile
            let tile_rect = tiles::get_tile_rect(tile, scale);
            for root_id in &root_children {
                if has_gather || tile_shapes.contains(root_id) {
                    self.emit_shape_steps(*root_id, tree, &tile_rect, scale);
                }
            }
        }
    }

    /// Emit Enter/Render/Exit steps for a shape and its descendants.
    fn emit_shape_steps(
        &mut self,
        shape_id: Uuid,
        tree: ShapesPoolRef,
        tile_rect: &skia::Rect,
        scale: f32,
    ) {
        let Some(shape) = tree.get(&shape_id) else {
            return;
        };

        if shape.hidden {
            return;
        }

        // Visibility check — is the shape in or near this tile?
        let selrect = shape.selrect();
        let extrect = shape.extrect(tree, scale);
        let is_container = matches!(
            shape.shape_type,
            Type::Frame(_) | Type::Group(_)
        );
        let check_rect = if is_container { extrect } else { selrect };
        if !check_rect.intersects(*tile_rect) {
            return;
        }

        if shape.is_recursive() {
            // Container: emit Enter, children, Exit
            self.schedule.push(RenderStep::Enter(shape_id));

            // children_ids() returns children in reversed order (topmost first).
            // The schedule is executed front-to-back, so we need bottom-first
            // (natural tree order) — reverse back to get correct paint order.
            let mut children = shape.children_ids(false);
            children.reverse();
            for child_id in &children {
                self.emit_shape_steps(*child_id, tree, tile_rect, scale);
            }

            self.schedule.push(RenderStep::Exit(shape_id));
        } else {
            // Leaf shape: emit Render
            self.schedule.push(RenderStep::Render(shape_id));
        }
    }
}

// ── RenderState replacement methods ─────────────────────────────────────
//
// These have the same public signatures as the methods gated with
// #[cfg(not(feature = "tile-scheduler"))] in render.rs.
// state.rs calls them without knowing which implementation runs.

#[cfg(feature = "tile-scheduler")]
impl RenderState {
    pub fn start_render_loop(
        &mut self,
        base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
        sync_render: bool,
    ) -> Result<()> {
        let _start = performance::begin_timed_log!("start_render_loop");
        let scale = self.get_scale();

        self.tile_viewbox.update(self.viewbox, scale);
        self.focus_mode.reset();

        performance::begin_measure!("render");
        performance::begin_measure!("start_render_loop");

        self.reset_canvas();
        let surface_ids = SurfaceId::Strokes as u32
            | SurfaceId::Fills as u32
            | SurfaceId::InnerShadows as u32
            | SurfaceId::TextDropShadows as u32;
        self.surfaces.apply_mut(surface_ids, |s| {
            s.canvas().scale((scale, scale));
        });

        let viewbox_cache_size = crate::render::get_cache_size(self.viewbox, scale);
        let cached_viewbox_cache_size = crate::render::get_cache_size(self.cached_viewbox, scale);
        if viewbox_cache_size.width > cached_viewbox_cache_size.width
            || viewbox_cache_size.height > cached_viewbox_cache_size.height
        {
            self.surfaces
                .resize_cache(viewbox_cache_size, crate::render::VIEWPORT_INTEREST_AREA_THRESHOLD)?;
        }

        performance::begin_measure!("tile_grid_rebuild");
        self.tile_grid.rebuild(tree, &self.tile_viewbox, scale);
        self.tile_grid.reset();
        performance::end_measure!("tile_grid_rebuild");

        self.nested_fills.clear();
        self.nested_blurs.clear();
        self.nested_shadows.clear();
        self.current_tile = None;
        self.render_in_progress = true;

        self.apply_drawing_to_render_canvas(None, SurfaceId::Current);

        if sync_render {
            self.run_schedule(tree, timestamp, false)?;
            self.flush_and_submit();
        } else {
            self.run_schedule(tree, timestamp, true)?;
            self.flush_and_submit();
            if self.render_in_progress {
                self.cancel_animation_frame();
                self.render_request_id = Some(wapi::request_animation_frame!());
            } else {
                performance::end_measure!("render");
            }
        }

        performance::end_measure!("start_render_loop");
        performance::end_timed_log!("start_render_loop", _start);
        Ok(())
    }

    pub fn process_animation_frame(
        &mut self,
        _base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<()> {
        performance::begin_measure!("process_animation_frame");
        if self.render_in_progress {
            if tree.len() != 0 {
                self.run_schedule(tree, timestamp, true)?;
            }
            self.flush_and_submit();

            if self.render_in_progress {
                self.cancel_animation_frame();
                self.render_request_id = Some(wapi::request_animation_frame!());
            } else {
                performance::end_measure!("render");
            }
        }
        performance::end_measure!("process_animation_frame");
        Ok(())
    }

    pub fn render_shape_tree_sync(
        &mut self,
        _base_object: Option<&Uuid>,
        tree: ShapesPoolRef,
        timestamp: i32,
    ) -> Result<()> {
        if tree.len() != 0 {
            self.run_schedule(tree, timestamp, false)?;
        }
        self.flush_and_submit();
        Ok(())
    }

    /// The main render loop. Iterates the pre-computed schedule, calling
    /// existing rendering primitives for each step.
    fn run_schedule(
        &mut self,
        tree: ShapesPoolRef,
        timestamp: i32,
        can_yield: bool,
    ) -> Result<()> {
        let mut iteration = 0;
        let mut tile_has_content = false;

        while let Some(step) = self.tile_grid.next() {
            match step {
                RenderStep::SetTile(tile) => {
                    // Finalize previous tile if any
                    if let Some(_prev_tile) = self.current_tile {
                        let tile_rect = self.get_current_tile_bounds()?;
                        if tile_has_content {
                            self.apply_render_to_final_canvas(tile_rect)?;
                        } else {
                            // Empty tile: draw background directly on Target
                            self.surfaces.apply_mut(SurfaceId::Target as u32, |s| {
                                let mut paint = skia::Paint::default();
                                paint.set_color(self.background_color);
                                s.canvas().draw_rect(tile_rect, &paint);
                            });
                        }
                    }

                    // Always clear Current before each new tile — matches original
                    // renderer which clears unconditionally every iteration.
                    self.surfaces
                        .canvas(SurfaceId::Current)
                        .clear(self.background_color);

                    tile_has_content = false;
                    self.update_render_context(tile);

                    // If cached, blit and skip to next tile
                    if self.surfaces.has_cached_tile_surface(tile) {
                        let tile_rect = self.get_current_tile_bounds()?;
                        self.surfaces.draw_cached_tile_surface(
                            tile,
                            tile_rect,
                            self.background_color,
                        );
                        self.current_tile = None; // don't finalize on next SetTile
                        self.tile_grid.skip_to_next_tile();
                        continue;
                    }
                }

                RenderStep::Enter(id) => {
                    let Some(element) = tree.get(&id) else {
                        continue;
                    };

                    if element.hidden {
                        // Skip this container and its children
                        self.skip_container_steps();
                        continue;
                    }

                    self.focus_mode.enter(&id);

                    if self.focus_mode.is_active() {
                        self.render_shape_enter(element, false, SurfaceId::Current);
                    }
                }

                RenderStep::Render(id) => {
                    let Some(element) = tree.get(&id) else {
                        continue;
                    };

                    if element.hidden {
                        continue;
                    }

                    // Leaf shapes don't have Enter/Exit steps, so we must
                    // enter/exit focus_mode here to match the current renderer's
                    // behavior (which calls enter() on every shape visit).
                    self.focus_mode.enter(&id);

                    if !self.focus_mode.is_active() {
                        continue;
                    }

                    tile_has_content = true;
                    let scale = self.get_scale();

                    // Visibility check against current tile
                    let selrect = element.selrect();
                    if !selrect.intersects(self.render_area_with_margins) {
                        continue;
                    }

                    // Background blur
                    self.render_background_blur(element, SurfaceId::Current);

                    // Glass effect
                    if let Some(glass) = element.glass.as_ref().filter(|g| !g.hidden) {
                        crate::render::glass::render_glass(
                            self,
                            element,
                            glass,
                            SurfaceId::Current,
                        );
                    }

                    // Drop shadows
                    let translation = self
                        .surfaces
                        .get_render_context_translation(self.render_area, scale);

                    let skip_shadows = self.options.is_fast_mode();
                    if !skip_shadows && !matches!(element.shape_type, Type::Text(_)) {
                        let mut extrect: Option<skia::Rect> = None;
                        // Simplified shadow rendering — delegating to existing method
                        // would require the full NodeRenderState context.
                        // For now, we render fills/strokes which includes shadow compositing.
                    }

                    // Fills, strokes, inner shadows
                    self.render_shape(
                        element,
                        None, // clip_bounds
                        SurfaceId::Fills,
                        SurfaceId::Strokes,
                        SurfaceId::InnerShadows,
                        SurfaceId::TextDropShadows,
                        true,
                        None,
                        None,
                        None,
                        SurfaceId::Current,
                    )?;

                    self.surfaces
                        .canvas(SurfaceId::DropShadows)
                        .clear(skia::Color::TRANSPARENT);

                    // Composite sub-surfaces into Current
                    self.apply_drawing_to_render_canvas(Some(element), SurfaceId::Current);

                    self.focus_mode.exit(&id);
                }

                RenderStep::Exit(id) => {
                    let Some(element) = tree.get(&id) else {
                        continue;
                    };

                    if self.focus_mode.is_active() {
                        self.render_shape_exit(element, false, None, SurfaceId::Current)?;
                    }

                    self.focus_mode.exit(&id);
                }
            }

            if can_yield {
                iteration += 1;
                if self.should_stop_rendering(iteration, timestamp) {
                    return Ok(());
                }
            }
        }

        // Finalize last tile
        if self.current_tile.is_some() {
            let tile_rect = self.get_current_tile_bounds()?;
            if tile_has_content {
                self.apply_render_to_final_canvas(tile_rect)?;
            } else {
                self.surfaces.apply_mut(SurfaceId::Target as u32, |s| {
                    let mut paint = skia::Paint::default();
                    paint.set_color(self.background_color);
                    s.canvas().draw_rect(tile_rect, &paint);
                });
            }
        }

        self.render_in_progress = false;
        self.surfaces.gc();
        self.cached_viewbox = self.viewbox;

        if self.options.is_debug_visible() {
            crate::render::debug::render(self);
        }

        crate::render::ui::render(self, tree);
        crate::render::debug::render_wasm_label(self);

        Ok(())
    }

    /// Skip past matching Enter/Exit pairs when a container is hidden.
    fn skip_container_steps(&mut self) {
        let mut depth = 1;
        while depth > 0 {
            match self.tile_grid.next() {
                Some(RenderStep::Enter(_)) => depth += 1,
                Some(RenderStep::Exit(_)) => depth -= 1,
                None => break,
                _ => {}
            }
        }
    }

    // ── Tile management methods (same signatures as current impl) ────

    pub fn get_tiles_for_shape(&mut self, shape: &Shape, tree: ShapesPoolRef) -> TileRect {
        let scale = self.get_scale();
        let extrect = self.get_cached_extrect(shape, tree, scale);
        let tile_size = tiles::get_tile_size(scale);
        let shape_tiles = tiles::get_tiles_for_rect(extrect, tile_size);
        let interest_rect = &self.tile_viewbox.interest_rect;

        let ix1 = shape_tiles.x1().max(interest_rect.x1());
        let iy1 = shape_tiles.y1().max(interest_rect.y1());
        let ix2 = shape_tiles.x2().min(interest_rect.x2());
        let iy2 = shape_tiles.y2().min(interest_rect.y2());

        if ix1 <= ix2 && iy1 <= iy2 {
            TileRect(ix1, iy1, ix2, iy2)
        } else {
            TileRect(0, 0, -1, -1)
        }
    }

    pub fn update_shape_tiles(
        &mut self,
        shape: &Shape,
        tree: ShapesPoolRef,
    ) -> HashSet<Tile> {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree);
        let has_gather = TileGrid::shape_has_gather(shape);
        let z_index = shape.z_index();

        // Remove from old tiles
        let old_tiles: Vec<_> = self
            .tile_grid
            .get_tiles_of(&shape.id)
            .map_or(Vec::new(), |t| t.iter().copied().collect());

        let mut result = HashSet::with_capacity(old_tiles.len());

        for tile in old_tiles {
            self.tile_grid.remove_shape_at(tile, shape.id);
            result.insert(tile);
        }

        // Add to new tiles
        for tile in (rsx..=rex).flat_map(|x| (rsy..=rey).map(move |y| Tile::from(x, y))) {
            self.tile_grid.add_shape_at(
                tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    has_gather,
                },
            );
            result.insert(tile);
        }

        result
    }

    pub fn update_shape_tiles_incremental(
        &mut self,
        shape: &Shape,
        tree: ShapesPoolRef,
    ) -> Vec<Tile> {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree);
        let has_gather = TileGrid::shape_has_gather(shape);
        let z_index = shape.z_index();

        let old_tiles: HashSet<Tile> = self
            .tile_grid
            .get_tiles_of(&shape.id)
            .map_or(HashSet::new(), |tiles| tiles.iter().copied().collect());

        let new_tiles: HashSet<Tile> = (rsx..=rex)
            .flat_map(|x| (rsy..=rey).map(move |y| Tile::from(x, y)))
            .collect();

        let removed: Vec<_> = old_tiles.difference(&new_tiles).copied().collect();
        let added: Vec<_> = new_tiles.difference(&old_tiles).copied().collect();

        for tile in &removed {
            self.tile_grid.remove_shape_at(*tile, shape.id);
        }

        for tile in &added {
            self.tile_grid.add_shape_at(
                *tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    has_gather,
                },
            );
        }

        Vec::new()
    }

    pub fn add_shape_tiles(
        &mut self,
        shape: &Shape,
        tree: ShapesPoolRef,
    ) -> Vec<Tile> {
        let TileRect(rsx, rsy, rex, rey) = self.get_tiles_for_shape(shape, tree);
        let has_gather = TileGrid::shape_has_gather(shape);
        let z_index = shape.z_index();
        let mut result = Vec::new();

        for tile in (rsx..=rex).flat_map(|x| (rsy..=rey).map(move |y| Tile::from(x, y))) {
            self.tile_grid.add_shape_at(
                tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    has_gather,
                },
            );
            result.push(tile);
        }

        result
    }

    pub fn remove_cached_tile(&mut self, tile: Tile) {
        self.surfaces.remove_cached_tile_surface(tile);
    }

    pub fn rebuild_tile_index(&mut self, tree: ShapesPoolRef) {
        let scale = self.get_scale();
        self.tile_grid.rebuild(tree, &self.tile_viewbox, scale);
    }

    pub fn rebuild_tiles_shallow(&mut self, tree: ShapesPoolRef) {
        performance::begin_measure!("rebuild_tiles_shallow");

        self.rebuild_tile_index(tree);

        if self.zoom_changed() {
            self.surfaces.remove_cached_tiles(self.background_color);
        } else {
            self.surfaces.invalidate_tile_cache();
        }

        performance::end_measure!("rebuild_tiles_shallow");
    }

    pub fn rebuild_tiles_from(&mut self, tree: ShapesPoolRef, _base_id: Option<&Uuid>) {
        performance::begin_measure!("rebuild_tiles");

        let scale = self.get_scale();
        self.tile_grid.rebuild(tree, &self.tile_viewbox, scale);

        self.surfaces.remove_cached_tiles(self.background_color);
        // Invalidate all cached tiles
        for tile in self.tile_grid.grid.keys().copied().collect::<Vec<_>>() {
            self.remove_cached_tile(tile);
        }

        performance::end_measure!("rebuild_tiles");
    }

    pub fn rebuild_touched_tiles(&mut self, tree: ShapesPoolRef) {
        performance::begin_measure!("rebuild_touched_tiles");

        let touched = std::mem::take(&mut self.touched_ids);
        let scale = self.get_scale();
        let affected = self.tile_grid.update_touched(
            &touched,
            tree,
            &self.tile_viewbox,
            scale,
        );

        for tile in affected {
            self.remove_cached_tile(tile);
        }

        performance::end_measure!("rebuild_touched_tiles");
    }

    pub fn update_tiles_shapes(
        &mut self,
        shape_ids: &[Uuid],
        tree: ShapesPoolMutRef<'_>,
    ) -> Result<()> {
        let mut all_tiles = HashSet::new();
        for shape_id in shape_ids {
            if let Some(shape) = tree.get(shape_id) {
                all_tiles.extend(self.update_shape_tiles(shape, tree));
            }
        }
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        Ok(())
    }

    pub fn rebuild_modifier_tiles(
        &mut self,
        tree: ShapesPoolMutRef<'_>,
        ids: Vec<Uuid>,
    ) -> Result<()> {
        use crate::shapes::all_with_ancestors;

        // Get all ancestors of modified shapes so their extrects/tiles update too
        let ancestors = all_with_ancestors(&ids, tree, false);

        // Re-index affected shapes and invalidate their tiles
        let mut all_tiles = HashSet::new();
        for shape_id in &ancestors {
            if let Some(shape) = tree.get(shape_id) {
                all_tiles.extend(self.update_shape_tiles(shape, tree));
            }
        }
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        Ok(())
    }

    pub fn render_shape_pixels(
        &mut self,
        id: &Uuid,
        tree: ShapesPoolRef,
        scale: f32,
        timestamp: i32,
    ) -> Result<(Vec<u8>, i32, i32)> {
        let target_surface = SurfaceId::Export;

        self.focus_mode.clear();

        self.surfaces
            .canvas(target_surface)
            .clear(skia::Color::TRANSPARENT);

        if tree.len() != 0 {
            let shape = tree.get(id).unwrap();
            let mut extrect = shape.extrect(tree, scale);
            self.export_context = Some((extrect, scale));
            let margins = self.surfaces.margins();
            extrect.offset((margins.width as f32 / scale, margins.height as f32 / scale));

            self.surfaces.resize_export_surface(scale, extrect);
            self.render_area = extrect;
            self.render_area_with_margins = extrect;
            self.surfaces.update_render_context(extrect, scale);

            // For export, do a simple depth-first render without tile scheduling
            self.render_export_subtree(*id, tree, target_surface, scale)?;
        }

        self.export_context = None;

        self.surfaces
            .flush_and_submit(&mut self.gpu_state, target_surface);

        let image = self.surfaces.snapshot(target_surface);
        let data = image
            .encode(
                &mut self.gpu_state.context,
                skia::EncodedImageFormat::PNG,
                100,
            )
            .expect("PNG encode failed");
        let skia::ISize { width, height } = image.dimensions();

        Ok((data.as_bytes().to_vec(), width, height))
    }

    /// Simple depth-first render for export (no tile scheduling needed).
    fn render_export_subtree(
        &mut self,
        shape_id: Uuid,
        tree: ShapesPoolRef,
        target: SurfaceId,
        scale: f32,
    ) -> Result<()> {
        let Some(shape) = tree.get(&shape_id) else {
            return Ok(());
        };

        if shape.hidden {
            return Ok(());
        }

        self.focus_mode.enter(&shape_id);

        if self.focus_mode.is_active() {
            if shape.is_recursive() {
                self.render_shape_enter(shape, false, target);

                let children = shape.children_ids(false);
                for child_id in &children {
                    self.render_export_subtree(*child_id, tree, target, scale)?;
                }

                self.render_shape_exit(shape, false, None, target)?;
            } else {
                // Render the shape
                self.render_background_blur(shape, target);

                if let Some(glass) = shape.glass.as_ref().filter(|g| !g.hidden) {
                    crate::render::glass::render_glass(self, shape, glass, target);
                }

                self.render_shape(
                    shape,
                    None,
                    SurfaceId::Fills,
                    SurfaceId::Strokes,
                    SurfaceId::InnerShadows,
                    SurfaceId::TextDropShadows,
                    true,
                    None,
                    None,
                    None,
                    target,
                )?;

                self.surfaces
                    .canvas(SurfaceId::DropShadows)
                    .clear(skia::Color::TRANSPARENT);

                self.apply_drawing_to_render_canvas(Some(shape), target);
            }
        }

        self.focus_mode.exit(&shape_id);
        Ok(())
    }
}

// ── Benchmarks ──────────────────────────────────────────────────────────

#[cfg(test)]
mod bench {
    use super::*;
    use std::time::Instant;

    fn make_uuid(n: u64) -> Uuid {
        Uuid::from_u64_pair(0, n)
    }

    #[test]
    fn bench_tilegrid_add_1k() {
        tilegrid_add_n(1_000);
    }

    #[test]
    fn bench_tilegrid_add_10k() {
        tilegrid_add_n(10_000);
    }

    #[test]
    fn bench_tilegrid_add_50k() {
        tilegrid_add_n(50_000);
    }

    fn tilegrid_add_n(n: usize) {
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);
        let mut grid = TileGrid::new();

        let start = Instant::now();
        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);

            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid add {n} shapes: {:.3}ms ({:.1}µs/shape)",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / n as f64
        );
    }

    #[test]
    fn bench_tilegrid_move_1k() {
        tilegrid_move_n(1_000);
    }

    #[test]
    fn bench_tilegrid_move_10k() {
        tilegrid_move_n(10_000);
    }

    fn tilegrid_move_n(n: usize) {
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);
        let mut grid = TileGrid::new();

        // Setup
        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }

        // Benchmark: move each shape by (10, 10)
        let start = Instant::now();
        for i in 0..n {
            let id = make_uuid(i as u64);
            let old_x = (i % 200) as f32 * 50.0;
            let old_y = (i / 200) as f32 * 50.0;
            let new_x = old_x + 10.0;
            let new_y = old_y + 10.0;

            let old_rect = skia::Rect::from_xywh(old_x, old_y, 100.0, 80.0);
            let old_tiles = tiles::get_tiles_for_rect(old_rect, tile_size);
            for tx in old_tiles.x1()..=old_tiles.x2() {
                for ty in old_tiles.y1()..=old_tiles.y2() {
                    grid.remove_shape_at(Tile::from(tx, ty), id);
                }
            }

            let new_rect = skia::Rect::from_xywh(new_x, new_y, 100.0, 80.0);
            let new_tiles = tiles::get_tiles_for_rect(new_rect, tile_size);
            for tx in new_tiles.x1()..=new_tiles.x2() {
                for ty in new_tiles.y1()..=new_tiles.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid move {n} shapes: {:.3}ms ({:.1}µs/shape)",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / n as f64
        );
    }

    #[test]
    fn bench_tilegrid_query_viewport() {
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);
        let n = 10_000;
        let mut grid = TileGrid::new();

        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }

        let viewport = skia::Rect::from_xywh(500.0, 500.0, 1920.0, 1080.0);
        let visible_tiles = tiles::get_tiles_for_rect(viewport, tile_size);
        let tile_count =
            (visible_tiles.width() + 1) as usize * (visible_tiles.height() + 1) as usize;

        let iterations = 100;
        let start = Instant::now();
        let mut total_shapes = 0usize;
        for _ in 0..iterations {
            for tx in visible_tiles.x1()..=visible_tiles.x2() {
                for ty in visible_tiles.y1()..=visible_tiles.y2() {
                    if let Some(shapes) = grid.get_shapes_at(Tile::from(tx, ty)) {
                        total_shapes += shapes.len();
                    }
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid query viewport ({tile_count} tiles, {n} shapes): \
             {:.3}ms/query, {total_shapes} shape refs over {iterations} iterations",
            elapsed.as_secs_f64() * 1000.0 / iterations as f64
        );
    }

    #[test]
    fn bench_tilegrid_full_rebuild_10k() {
        tilegrid_full_rebuild_n(10_000);
    }

    #[test]
    fn bench_tilegrid_full_rebuild_50k() {
        tilegrid_full_rebuild_n(50_000);
    }

    fn tilegrid_full_rebuild_n(n: usize) {
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);

        let rects: Vec<_> = (0..n)
            .map(|i| {
                let x = (i % 200) as f32 * 50.0;
                let y = (i / 200) as f32 * 50.0;
                (
                    make_uuid(i as u64),
                    skia::Rect::from_xywh(x, y, 100.0, 80.0),
                )
            })
            .collect();

        let iterations = 10;
        let start = Instant::now();
        for _ in 0..iterations {
            let mut grid = TileGrid::new();
            for (i, (id, rect)) in rects.iter().enumerate() {
                let tile_rect = tiles::get_tiles_for_rect(*rect, tile_size);
                for tx in tile_rect.x1()..=tile_rect.x2() {
                    for ty in tile_rect.y1()..=tile_rect.y2() {
                        grid.add_shape_at(
                            Tile::from(tx, ty),
                            ShapeEntry {
                                id: *id,
                                z_index: i as i32,
                                has_gather: false,
                            },
                        );
                    }
                }
            }
            grid.invalidate();
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid full rebuild {n} shapes: {:.3}ms/rebuild",
            elapsed.as_secs_f64() * 1000.0 / iterations as f64
        );
    }

    #[test]
    fn bench_tilegrid_single_shape_drag_latency() {
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);
        let n = 10_000;
        let mut grid = TileGrid::new();

        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }

        let drag_id = make_uuid(500);
        let iterations = 1000;
        let mut x = 250.0_f32 * 50.0;
        let y = 2.0_f32 * 50.0;

        let start = Instant::now();
        for _ in 0..iterations {
            let old_rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let old_tiles = tiles::get_tiles_for_rect(old_rect, tile_size);
            for tx in old_tiles.x1()..=old_tiles.x2() {
                for ty in old_tiles.y1()..=old_tiles.y2() {
                    grid.remove_shape_at(Tile::from(tx, ty), drag_id);
                }
            }
            x += 2.0;
            let new_rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let new_tiles = tiles::get_tiles_for_rect(new_rect, tile_size);
            for tx in new_tiles.x1()..=new_tiles.x2() {
                for ty in new_tiles.y1()..=new_tiles.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id: drag_id,
                            z_index: 500,
                            has_gather: false,
                        },
                    );
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid single shape drag ({n} scene, {iterations} moves): \
             {:.3}ms total, {:.1}µs/move",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / iterations as f64
        );
    }

    #[test]
    fn bench_tilegrid_spiral_generation() {
        let sizes = [(5, 4), (10, 8), (20, 15), (40, 30)];

        for (w, h) in sizes {
            let rect = TileRect(0, 0, w, h);
            let total = (w + 1) * (h + 1);

            let iterations = 1000;
            let start = Instant::now();
            for _ in 0..iterations {
                let _ = TileGrid::generate_spiral(&rect);
            }
            let elapsed = start.elapsed();
            println!(
                "[bench] TileGrid spiral {w}x{h} ({total} tiles): {:.3}ms/gen",
                elapsed.as_secs_f64() * 1000.0 / iterations as f64
            );
        }
    }

    #[test]
    fn bench_tilegrid_topological_sort() {
        // Create a scenario with gather dependencies
        let mut grid = TileGrid::new();
        let scale = 1.0;
        let tile_size = tiles::get_tile_size(scale);

        // Add 1000 normal shapes
        for i in 0..1000 {
            let id = make_uuid(i as u64);
            let x = (i % 50) as f32 * 200.0;
            let y = (i / 50) as f32 * 200.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: i as i32,
                            has_gather: false,
                        },
                    );
                }
            }
        }

        // Add 5 gather shapes spanning multiple tiles
        for i in 0..5 {
            let id = make_uuid(10000 + i as u64);
            let x = (i * 3) as f32 * 200.0;
            let rect = skia::Rect::from_xywh(x, 0.0, 800.0, 600.0);
            let tile_rect = tiles::get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    grid.add_shape_at(
                        Tile::from(tx, ty),
                        ShapeEntry {
                            id,
                            z_index: 5000 + i as i32,
                            has_gather: true,
                        },
                    );
                }
            }
        }

        let interest = TileRect(0, 0, 20, 15);
        let viewbox = crate::view::Viewbox::new(1920.0, 1080.0);
        let tile_viewbox = TileViewbox::new_with_interest(viewbox, 1, scale);

        let spiral = TileGrid::generate_spiral(&interest);

        // Build deps manually for benchmark
        let iterations = 100;
        let start = Instant::now();
        for _ in 0..iterations {
            // Simulate dependency building from gather shapes
            let mut deps: HashMap<Tile, HashSet<Tile>> = HashMap::new();
            for (tile, entries) in &grid.grid {
                for entry in entries {
                    if entry.has_gather {
                        // Add dependencies on neighboring tiles
                        for dx in -2..=2 {
                            for dy in -2..=2 {
                                if dx == 0 && dy == 0 {
                                    continue;
                                }
                                let dep = Tile::from(tile.x() + dx, tile.y() + dy);
                                if grid.grid.contains_key(&dep) {
                                    deps.entry(*tile).or_default().insert(dep);
                                }
                            }
                        }
                    }
                }
            }

            let _ = grid.topological_sort(&spiral, &deps, &tile_viewbox);
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileGrid topo sort (1000 shapes, 5 gathers): {:.3}ms/sort",
            elapsed.as_secs_f64() * 1000.0 / iterations as f64
        );
    }
}
