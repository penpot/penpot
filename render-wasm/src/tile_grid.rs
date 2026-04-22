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
    /// Depth-first paint rank (bottom-first), populated during TileGrid::rebuild
    /// or update_touched via renumber_paint_order. Entries constructed outside
    /// those passes use 0 as a placeholder and get corrected on the next
    /// renumber, which always runs before build_dependency_graph.
    pub paint_order: u32,
    pub has_gather: bool,
}

/// A contiguous run of shapes within one tile, bounded by that tile's
/// gather-barrier paint orders. Populated by `compute_bands`.
#[derive(Debug, Clone)]
pub struct Band {
    /// Shape ids in paint order (ascending).
    pub shapes: Vec<Uuid>,
    /// Lowest `paint_order` among entries in this band.
    pub min_paint_order: u32,
    /// Highest `paint_order` among entries in this band.
    pub max_paint_order: u32,
    /// The gather at the head of the band, if this band starts at a barrier.
    pub gather_at_head: Option<Uuid>,
}

/// Scheduler node: a specific band within a specific tile. `band_index` is
/// T-local and packed — 0, 1, 2, … per tile — no cross-tile meaning.
#[derive(Clone, Copy, Eq, PartialEq, Hash, Debug)]
pub struct BandKey {
    pub tile: Tile,
    pub band_index: u32,
}

impl BandKey {
    fn new(tile: Tile, band_index: u32) -> Self {
        Self { tile, band_index }
    }
}

/// How a band is finalized when its shape steps end. Pre-computed at
/// schedule-build time so `run_schedule` never has to infer state at
/// yield/resume boundaries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FinalizeKind {
    /// Non-last band of a multi-band tile. Push this band's Current content
    /// to Target (so later peer gather bands can sample it) and snapshot
    /// Current into the interband cache so this tile's next band can
    /// restore it.
    Intermediate,
    /// Tile's last band, and the band had shapes to render. Apply Current
    /// to the final canvas and drop the interband cache entry.
    LastContent,
    /// Tile's last band, no shapes in the band (synthetic empty-tile bg
    /// clear). Draw bg directly on Target — faster than touching Current.
    LastBg,
}

/// A single step in the pre-computed render schedule.
#[derive(Debug, Clone)]
pub enum RenderStep {
    /// Begin rendering a band: set up the tile context, and either clear
    /// Current (is_first) or restore Current from the interband cache
    /// (non-first band). `is_last` is retained for tests/debug only —
    /// finalize semantics live in the paired `FinalizeBand` step.
    SetTileBand {
        tile: Tile,
        band_index: u32,
        is_first: bool,
        is_last: bool,
    },
    /// Finalize the band that `SetTileBand` opened. The `kind` is
    /// determined at build time so a yield between Render and
    /// FinalizeBand cannot corrupt the finalize path.
    FinalizeBand {
        tile: Tile,
        kind: FinalizeKind,
    },
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
struct BandPriority {
    key: BandKey,
    /// Priority group: 0 = visible, 1 = interest-only.
    group: u8,
    /// Spiral position of `key.tile` (lower = closer to center).
    spiral_index: usize,
}

impl Ord for BandPriority {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        // BinaryHeap is a max-heap, so reverse: lowest group wins, then lowest
        // spiral_index, then lowest band_index so earlier bands on a tile
        // precede later ones when both are ready.
        other
            .group
            .cmp(&self.group)
            .then(other.spiral_index.cmp(&self.spiral_index))
            .then(other.key.band_index.cmp(&self.key.band_index))
    }
}

impl PartialOrd for BandPriority {
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

    // ── Band model (populated per rebuild by `compute_bands`) ────────
    /// Tile → packed list of bands (band_index 0, 1, 2, …). Empty vec or
    /// missing key means the tile has no shapes in the interest rect.
    bands: HashMap<Tile, Vec<Band>>,

    // ── Render schedule ──────────────────────────────────────────────
    /// Pre-computed flat render schedule. Index 0 executes first.
    schedule: Vec<RenderStep>,
    /// Current position for yield/resume across animation frames.
    cursor: usize,
}

/// Children of `shape` in paint order (bottom-first), honouring flex/grid
/// `order` overrides. Mirrors `render::sort_z_index` but returns bottom-first
/// so a depth-first counter walks shapes in paint sequence.
fn paint_order_children(shape: &Shape, tree: ShapesPoolRef) -> Vec<Uuid> {
    let mut ids = shape.children_ids(false);
    ids.reverse(); // children_ids is topmost-first → flip to bottom-first
    if shape.has_layout() {
        ids.sort_by(|a, b| {
            let za = tree.get(a).map(|s| s.z_index()).unwrap_or(0);
            let zb = tree.get(b).map(|s| s.z_index()).unwrap_or(0);
            za.cmp(&zb) // ascending = bottom-first
        });
    }
    ids
}

impl TileGrid {
    pub fn new() -> Self {
        TileGrid {
            grid: HashMap::new(),
            index: HashMap::new(),
            bands: HashMap::new(),
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
        // Insert in paint-order-sorted position.
        let pos = entries
            .binary_search_by(|e| e.paint_order.cmp(&entry.paint_order))
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
        self.bands.clear();
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

    /// Skip forward to the next SetTileBand step (used when the current
    /// tile's band is cached).
    pub fn skip_to_next_tile(&mut self) {
        while self.cursor < self.schedule.len() {
            if matches!(self.schedule[self.cursor], RenderStep::SetTileBand { .. }) {
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
        self.bands.clear();
        self.schedule.clear();
        self.cursor = 0;

        let tile_size = tiles::get_tile_size(scale);
        let interest_rect = &tile_viewbox.interest_rect;

        // Step 1: Walk shape tree, assign shapes to tiles (and assign paint_order).
        let root_id = Uuid::nil();
        let mut paint_counter: u32 = 0;
        if let Some(root) = tree.get(&root_id) {
            for child_id in paint_order_children(root, tree) {
                self.index_shape_recursive(
                    child_id,
                    tree,
                    tile_size,
                    interest_rect,
                    scale,
                    &mut paint_counter,
                );
            }
        } else {
        }

        // Step 2: Generate spiral
        let spiral = Self::generate_spiral(interest_rect);

        // Step 3: Compute bands per tile (barriers = paint_orders of gathers
        // whose sample regions reach this tile, plus the tile's own gathers).
        self.compute_bands(tree, tile_size, interest_rect, scale);

        // Step 4: Build dependency graph over BandKeys
        let deps = self.build_dependency_graph(tree, tile_size, interest_rect, scale);

        // Step 5: Topological sort with priority
        let sorted_bands = self.topological_sort(
            &spiral,
            &deps,
            tile_viewbox,
        );

        // Step 6: Flatten into render schedule
        self.build_schedule(&sorted_bands, tree, scale);

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
                                        // paint_order corrected below by
                                        // renumber_paint_order before the dep
                                        // graph runs — placeholder is fine.
                                        paint_order: 0,
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

        // Renumber paint_order across every entry so the dep-graph filter has
        // globally-consistent values (incremental updates above left most
        // entries with stale indices).
        self.renumber_paint_order(tree);

        // Rebuild schedule with updated index
        self.bands.clear();
        self.compute_bands(tree, tile_size, interest_rect, scale);
        let spiral = Self::generate_spiral(interest_rect);
        let deps = self.build_dependency_graph(tree, tile_size, interest_rect, scale);
        let sorted_bands = self.topological_sort(&spiral, &deps, tile_viewbox);
        self.build_schedule(&sorted_bands, tree, scale);

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
        paint_counter: &mut u32,
    ) {
        let Some(shape) = tree.get(&shape_id) else {
            return;
        };

        if shape.hidden {
            return;
        }

        let paint_order = *paint_counter;
        *paint_counter += 1;

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
                            paint_order,
                            has_gather,
                        },
                    );
                }
            }
        }

        // Recurse into children in paint order so paint_order stays consistent
        // with how the schedule will emit them (bottom-first, flex/grid-aware).
        if shape.is_recursive() {
            for child_id in paint_order_children(shape, tree) {
                self.index_shape_recursive(
                    child_id,
                    tree,
                    tile_size,
                    interest_rect,
                    scale,
                    paint_counter,
                );
            }
        }
    }

    /// Walk the shape tree depth-first and overwrite every existing
    /// `ShapeEntry.paint_order` in `self.grid` with a fresh counter. Used by
    /// incremental updates where new entries were added with a placeholder
    /// `paint_order: 0` and pre-existing entries carry stale values.
    fn renumber_paint_order(&mut self, tree: ShapesPoolRef) {
        let mut counter: u32 = 0;
        let root_id = Uuid::nil();
        if let Some(root) = tree.get(&root_id) {
            for child_id in paint_order_children(root, tree) {
                self.renumber_recurse(&mut counter, child_id, tree);
            }
        }
    }

    fn renumber_recurse(&mut self, counter: &mut u32, id: Uuid, tree: ShapesPoolRef) {
        let Some(shape) = tree.get(&id) else {
            return;
        };
        if shape.hidden {
            return;
        }
        let po = *counter;
        *counter += 1;
        // Update every ShapeEntry for this shape across the tiles it occupies.
        if let Some(tiles) = self.index.get(&id).cloned() {
            for tile in tiles {
                if let Some(entries) = self.grid.get_mut(&tile) {
                    for e in entries.iter_mut() {
                        if e.id == id {
                            e.paint_order = po;
                        }
                    }
                }
            }
        }
        if shape.is_recursive() {
            for child in paint_order_children(shape, tree) {
                self.renumber_recurse(counter, child, tree);
            }
        }
    }

    /// Compute per-tile bands. A band is a contiguous run of shapes (in
    /// paint-order) bounded by gather-barriers *relevant to that tile*. A
    /// tile's barriers are the `paint_order` values of every gather G such
    /// that T is in G's sample region (plus G's own tile, so the gather
    /// always sits at the head of a band in its home tile). Tiles outside
    /// every gather's sample region get exactly one band — the hot path
    /// stays identical to the pre-refactor scheduler.
    fn compute_bands(
        &mut self,
        tree: ShapesPoolRef,
        tile_size: f32,
        interest_rect: &TileRect,
        scale: f32,
    ) {
        // Pre-pass: for each gather, push its paint_order into every
        // sample-region tile's barrier list.
        let mut barriers: HashMap<Tile, Vec<u32>> = HashMap::new();

        // Collect (gather_tile, shape_id, paint_order) first to avoid
        // borrowing self.grid twice (compute_gather_sample_rect takes &self).
        let gathers: Vec<(Tile, Uuid, u32)> = self
            .grid
            .iter()
            .flat_map(|(tile, entries)| {
                entries.iter().filter(|e| e.has_gather).map(move |e| (*tile, e.id, e.paint_order))
            })
            .collect();

        for (gather_tile, shape_id, g_po) in gathers {
            let Some(shape) = tree.get(&shape_id) else {
                continue;
            };
            let sample_rect = self.compute_gather_sample_rect(shape, tree, scale);
            let sample_tiles = tiles::get_tiles_for_rect(sample_rect, tile_size);

            let sx1 = sample_tiles.x1().max(interest_rect.x1());
            let sy1 = sample_tiles.y1().max(interest_rect.y1());
            let sx2 = sample_tiles.x2().min(interest_rect.x2());
            let sy2 = sample_tiles.y2().min(interest_rect.y2());

            if sx1 <= sx2 && sy1 <= sy2 {
                for stx in sx1..=sx2 {
                    for sty in sy1..=sy2 {
                        barriers.entry(Tile::from(stx, sty)).or_default().push(g_po);
                    }
                }
            }
            // Ensure G's own tile carries the barrier even if the sample region
            // happens not to cover it (compute_gather_sample_rect could in
            // principle return a rect that excludes the shape's own tile).
            barriers.entry(gather_tile).or_default().push(g_po);
        }

        for v in barriers.values_mut() {
            v.sort();
            v.dedup();
        }

        // Per-tile band computation. Each shape falls into a "bucket" =
        // (number of barriers ≤ its paint_order). Consecutive entries with
        // the same bucket form a band. A shape whose paint_order equals a
        // barrier AND is itself a gather gets marked as the band's head.
        for (tile, entries) in &self.grid {
            if entries.is_empty() {
                continue;
            }

            let empty = Vec::new();
            let bars = barriers.get(tile).unwrap_or(&empty);

            // Entries are stored paint-order-sorted by add_shape_at; be
            // defensive and sort anyway (incremental paths can leave gaps).
            let mut sorted: Vec<&ShapeEntry> = entries.iter().collect();
            sorted.sort_by_key(|e| e.paint_order);

            let bucket_of = |po: u32| -> usize {
                // Count of barriers ≤ po (partition_point returns the first
                // index whose element is > po, which is exactly this count).
                bars.partition_point(|&b| b <= po)
            };

            let mut packed: Vec<Band> = Vec::new();
            let mut current_shapes: Vec<Uuid> = Vec::new();
            let mut current_min: u32 = u32::MAX;
            let mut current_max: u32 = 0;
            let mut current_gather_at_head: Option<Uuid> = None;
            let mut current_bucket: Option<usize> = None;

            for e in sorted {
                let bucket = bucket_of(e.paint_order);
                if current_bucket != Some(bucket) {
                    // Flush the previous band.
                    if !current_shapes.is_empty() {
                        packed.push(Band {
                            shapes: std::mem::take(&mut current_shapes),
                            min_paint_order: current_min,
                            max_paint_order: current_max,
                            gather_at_head: current_gather_at_head.take(),
                        });
                        current_min = u32::MAX;
                        current_max = 0;
                    }
                    current_bucket = Some(bucket);
                    // Mark as gather head iff this entry's po equals the
                    // barrier that opens this bucket AND the entry is itself
                    // a gather. (If two gathers share a paint_order — rare —
                    // we pick the first one as head; subsequent ones are
                    // still gathers but treated as regular band members.)
                    if e.has_gather && bucket > 0 && bars[bucket - 1] == e.paint_order {
                        current_gather_at_head = Some(e.id);
                    }
                }
                current_shapes.push(e.id);
                if e.paint_order < current_min {
                    current_min = e.paint_order;
                }
                if e.paint_order > current_max {
                    current_max = e.paint_order;
                }
            }
            if !current_shapes.is_empty() {
                packed.push(Band {
                    shapes: std::mem::take(&mut current_shapes),
                    min_paint_order: current_min,
                    max_paint_order: current_max,
                    gather_at_head: current_gather_at_head.take(),
                });
            }

            if !packed.is_empty() {
                self.bands.insert(*tile, packed);
            }
        }
    }

    /// Build dependency graph: for each band headed by a gather G, record
    /// which bands must render first — specifically, every band on every
    /// sample-region tile whose `max_paint_order < G.paint_order`. Because G
    /// contributes a barrier to each sample-region tile, those tiles' bands
    /// split cleanly at G's paint_order, so "below-G" bands are a well-
    /// defined prefix and every dep edge is acyclic by construction.
    fn build_dependency_graph(
        &self,
        tree: ShapesPoolRef,
        tile_size: f32,
        interest_rect: &TileRect,
        scale: f32,
    ) -> HashMap<BandKey, HashSet<BandKey>> {
        let mut deps: HashMap<BandKey, HashSet<BandKey>> = HashMap::new();

        for (tile, bands) in &self.bands {
            for (band_idx, band) in bands.iter().enumerate() {
                let Some(g_id) = band.gather_at_head else {
                    continue;
                };
                let g_po = band.min_paint_order; // the gather sits at the head
                let Some(shape) = tree.get(&g_id) else {
                    continue;
                };

                let sample_rect = self.compute_gather_sample_rect(shape, tree, scale);
                let sample_tiles = tiles::get_tiles_for_rect(sample_rect, tile_size);

                let sx1 = sample_tiles.x1().max(interest_rect.x1());
                let sy1 = sample_tiles.y1().max(interest_rect.y1());
                let sx2 = sample_tiles.x2().min(interest_rect.x2());
                let sy2 = sample_tiles.y2().min(interest_rect.y2());

                let key = BandKey::new(*tile, band_idx as u32);

                for stx in sx1..=sx2 {
                    for sty in sy1..=sy2 {
                        let sample_tile = Tile::from(stx, sty);
                        let Some(sample_bands) = self.bands.get(&sample_tile) else {
                            continue;
                        };
                        for (sidx, sband) in sample_bands.iter().enumerate() {
                            // Edges point toward strictly-lower paint-order
                            // bands. Self-band (sample_tile == tile && sidx
                            // == band_idx) is naturally excluded by the
                            // `max < g_po` test (the gather sits at the head
                            // so our own band's max >= g_po).
                            if sband.max_paint_order < g_po {
                                deps.entry(key)
                                    .or_default()
                                    .insert(BandKey::new(sample_tile, sidx as u32));
                            }
                        }
                    }
                }

                // Simpler & strictly stronger backstop: force EVERY
                // non-gather band strictly below this gather in paint order
                // to run before it, not just those whose tile lies in the
                // heuristic sample region. `compute_gather_sample_rect`
                // underestimates the shader's actual reach (displacement +
                // blur kernel can extend past the heuristic radius), leaving
                // tile-sized white holes where below-content tiles weren't
                // yet on Target when the backdrop snapshot was taken. This
                // pass closes every such gap at the cost of a few extra deps
                // — cheap at scheduling, zero extra runtime render work.
                for (other_tile, other_bands) in &self.bands {
                    for (sidx, sband) in other_bands.iter().enumerate() {
                        if sband.gather_at_head.is_some() {
                            continue; // other gather-headed band — unchanged
                        }
                        if sband.max_paint_order >= g_po {
                            continue; // not strictly below this gather
                        }
                        if other_tile == tile && sidx == band_idx {
                            continue; // self
                        }
                        deps.entry(key)
                            .or_default()
                            .insert(BandKey::new(*other_tile, sidx as u32));
                    }
                }
            }
        }

        deps
    }

    /// For a set of tiles whose content just changed, return every tile that
    /// hosts a gather shape whose sample region overlaps any of those tiles.
    /// Callers must invalidate the cache of these tiles so the gather effect
    /// re-samples the updated backdrop.
    pub fn gather_tiles_affected_by(
        &self,
        dirty_tiles: &HashSet<Tile>,
        tree: ShapesPoolRef,
        scale: f32,
    ) -> HashSet<Tile> {
        let tile_size = tiles::get_tile_size(scale);
        let mut result: HashSet<Tile> = HashSet::new();
        if dirty_tiles.is_empty() {
            return result;
        }
        for (gather_tile, entries) in &self.grid {
            for entry in entries {
                if !entry.has_gather {
                    continue;
                }
                let Some(shape) = tree.get(&entry.id) else {
                    continue;
                };
                let sample_rect = self.compute_gather_sample_rect(shape, tree, scale);
                let st = tiles::get_tiles_for_rect(sample_rect, tile_size);
                let overlaps = dirty_tiles.iter().any(|t| {
                    t.x() >= st.x1() && t.x() <= st.x2() && t.y() >= st.y1() && t.y() <= st.y2()
                });
                if overlaps {
                    result.insert(*gather_tile);
                }
            }
        }
        result
    }

    /// Compute the world-space rectangle a gather effect samples from.
    /// This is the shape bounds expanded by displacement + blur + frost radius.
    pub(crate) fn compute_gather_sample_rect(
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

    /// Generate every tile inside `rect` in roughly-concentric order (closest
    /// to the center first). Uses Chebyshev distance for ordering — cheaper
    /// than a literal spiral walk and, crucially, guaranteed to cover exactly
    /// the tiles in `rect` (no holes, no out-of-bounds tiles).
    fn generate_spiral(rect: &TileRect) -> Vec<Tile> {
        let columns = rect.width() + 1;
        let rows = rect.height() + 1;
        let total = columns * rows;
        if total <= 0 {
            return Vec::new();
        }

        let cx = rect.center_x();
        let cy = rect.center_y();

        let mut result: Vec<Tile> = Vec::with_capacity(total as usize);
        for ty in rect.y1()..=rect.y2() {
            for tx in rect.x1()..=rect.x2() {
                result.push(Tile::from(tx, ty));
            }
        }

        // Chebyshev distance keeps a "ring" ordering; tiebreak by a stable
        // clockwise angle so adjacent ring tiles stay locally coherent.
        result.sort_by(|a, b| {
            let da = (a.x() - cx).abs().max((a.y() - cy).abs());
            let db = (b.x() - cx).abs().max((b.y() - cy).abs());
            da.cmp(&db)
                .then_with(|| (a.y() - cy).cmp(&(b.y() - cy)))
                .then_with(|| (a.x() - cx).cmp(&(b.x() - cx)))
        });
        result
    }

    /// Topological sort over `BandKey` nodes using Kahn's algorithm. The
    /// band model is a strict DAG by construction (every edge points to a
    /// strictly-lower `max_paint_order`), so all nodes must emit. A
    /// `debug_assert` catches any regression.
    fn topological_sort(
        &self,
        spiral: &[Tile],
        deps: &HashMap<BandKey, HashSet<BandKey>>,
        tile_viewbox: &TileViewbox,
    ) -> Vec<BandKey> {
        // Build spiral index for priority tiebreak.
        let spiral_index: HashMap<Tile, usize> = spiral
            .iter()
            .enumerate()
            .map(|(i, t)| (*t, i))
            .collect();

        // Enumerate every spiral tile as ≥ 1 node. Tiles with bands emit
        // one BandKey per band. Tiles with no bands (viewport tiles whose
        // shapes moved away and the tile is now empty) emit a synthetic
        // BandKey(tile, 0) so `run_schedule` still fires `SetTileBand` on
        // them — without this the tile's pixels on Target persist from a
        // previous frame and we see stale content.
        let mut all_keys: Vec<BandKey> = Vec::new();
        for tile in spiral {
            match self.bands.get(tile) {
                Some(bands) if !bands.is_empty() => {
                    for idx in 0..bands.len() {
                        all_keys.push(BandKey::new(*tile, idx as u32));
                    }
                }
                _ => {
                    all_keys.push(BandKey::new(*tile, 0));
                }
            }
        }

        let key_set: HashSet<BandKey> = all_keys.iter().copied().collect();

        // In-degree + reverse adjacency restricted to keys that actually
        // exist (deps from/to tiles outside the spiral are ignored).
        let mut in_degree: HashMap<BandKey, usize> = HashMap::new();
        let mut reverse_deps: HashMap<BandKey, Vec<BandKey>> = HashMap::new();

        for (key, dep_set) in deps {
            if !key_set.contains(key) {
                continue;
            }
            let count = dep_set.iter().filter(|d| key_set.contains(d)).count();
            *in_degree.entry(*key).or_default() = count;

            for dep in dep_set {
                if key_set.contains(dep) {
                    reverse_deps.entry(*dep).or_default().push(*key);
                }
            }
        }

        // Seed the priority queue with bands that have no deps.
        let mut ready = BinaryHeap::new();
        for key in &all_keys {
            let degree = in_degree.get(key).copied().unwrap_or(0);
            if degree == 0 {
                let is_visible = tile_viewbox.visible_rect.contains(&key.tile);
                let group = if is_visible { 0 } else { 1 };
                ready.push(BandPriority {
                    key: *key,
                    group,
                    spiral_index: *spiral_index.get(&key.tile).unwrap_or(&usize::MAX),
                });
            }
        }

        let mut sorted = Vec::with_capacity(all_keys.len());

        while let Some(bp) = ready.pop() {
            sorted.push(bp.key);

            if let Some(dependents) = reverse_deps.get(&bp.key) {
                for dependent in dependents {
                    if let Some(degree) = in_degree.get_mut(dependent) {
                        *degree -= 1;
                        if *degree == 0 {
                            let is_visible =
                                tile_viewbox.visible_rect.contains(&dependent.tile);
                            let group = if is_visible { 0 } else { 1 };
                            ready.push(BandPriority {
                                key: *dependent,
                                group,
                                spiral_index: *spiral_index
                                    .get(&dependent.tile)
                                    .unwrap_or(&usize::MAX),
                            });
                        }
                    }
                }
            }
        }

        // The band model is a strict DAG — every node must emit. If this
        // ever trips, it's a correctness regression in compute_bands or
        // build_dependency_graph.
        debug_assert_eq!(
            sorted.len(),
            all_keys.len(),
            "band scheduler: dep graph had a cycle (emitted {}/{} bands)",
            sorted.len(),
            all_keys.len()
        );

        sorted
    }

    /// Flatten the sorted band list into a Vec<RenderStep>. For each band,
    /// emits `SetTileBand` + depth-first shape traversal filtered to shapes
    /// in this band's shape set.
    fn build_schedule(
        &mut self,
        sorted_bands: &[BandKey],
        tree: ShapesPoolRef,
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

        for key in sorted_bands {
            // A synthetic empty-tile BandKey has no matching Band. Emit a
            // single SetTileBand + FinalizeBand { LastBg } pair so
            // run_schedule clears Target at this tile rect (preventing stale
            // pixels from a previous frame when shapes move away).
            let (band_opt, is_first, is_last) = match self.bands.get(&key.tile) {
                Some(bands) if !bands.is_empty() => {
                    let total = bands.len();
                    let idx = key.band_index as usize;
                    let band = bands.get(idx);
                    let is_last = idx + 1 == total;
                    (band, idx == 0, is_last)
                }
                _ => (None, true, true),
            };

            self.schedule.push(RenderStep::SetTileBand {
                tile: key.tile,
                band_index: key.band_index,
                is_first,
                is_last,
            });

            let band_has_shapes = band_opt.is_some_and(|b| !b.shapes.is_empty());

            if let Some(band) = band_opt {
                if band_has_shapes {
                    // Emit Enter/Render/Exit for shapes in this band.
                    let band_shapes: HashSet<Uuid> =
                        band.shapes.iter().copied().collect();
                    let tile_rect = tiles::get_tile_rect(key.tile, scale);
                    for root_id in &root_children {
                        self.emit_shape_steps(
                            *root_id,
                            tree,
                            &tile_rect,
                            scale,
                            &band_shapes,
                        );
                    }
                }
            }

            // Finalize kind is fully determined here (build time) — no
            // runtime guessing, no state that a yield can drop.
            let kind = if !is_last {
                FinalizeKind::Intermediate
            } else if band_has_shapes {
                FinalizeKind::LastContent
            } else {
                FinalizeKind::LastBg
            };
            self.schedule.push(RenderStep::FinalizeBand {
                tile: key.tile,
                kind,
            });
        }
    }

    /// Emit Enter/Render/Exit steps for a shape and its descendants, filtered
    /// to shapes in `band_shapes`. Returns true if at least one step was
    /// emitted for this subtree (used by callers to skip empty Enter/Exit
    /// brackets).
    fn emit_shape_steps(
        &mut self,
        shape_id: Uuid,
        tree: ShapesPoolRef,
        tile_rect: &skia::Rect,
        scale: f32,
        band_shapes: &HashSet<Uuid>,
    ) -> bool {
        let Some(shape) = tree.get(&shape_id) else {
            return false;
        };

        if shape.hidden {
            return false;
        }

        // Visibility check — is the shape in or near this tile?
        // We previously used `selrect` for non-container shapes (cheaper than
        // computing extrect), but that misses scatter effects whose output
        // extends past the shape's natural outline into neighbouring tiles.
        // `extrect` already includes stroke/shadow/blur/scatter expansions,
        // is cached, and is a tight-enough superset that the over-emission
        // cost is negligible.
        let extrect = shape.extrect(tree, scale);
        if !extrect.intersects(*tile_rect) {
            return false;
        }

        if shape.is_recursive() {
            // Container: speculatively push Enter, recurse, then drop the
            // Enter if no child emitted anything for this band.
            let enter_pos = self.schedule.len();
            self.schedule.push(RenderStep::Enter(shape_id));

            let mut children = shape.children_ids(false);
            children.reverse();
            let mut any_child_emitted = false;
            for child_id in &children {
                if self.emit_shape_steps(*child_id, tree, tile_rect, scale, band_shapes) {
                    any_child_emitted = true;
                }
            }

            // The container itself is a shape in the band's shape list if and
            // only if it's a recursive shape with a paint_order matching the
            // barrier split. But we don't emit Render for recursive shapes,
            // so container membership in `band_shapes` just means "keep its
            // Enter/Exit brackets around any contained band content".
            let self_in_band = band_shapes.contains(&shape_id);

            if any_child_emitted || self_in_band {
                self.schedule.push(RenderStep::Exit(shape_id));
                true
            } else {
                // Drop speculative Enter — this container has no band content
                self.schedule.truncate(enter_pos);
                false
            }
        } else if band_shapes.contains(&shape_id) {
            self.schedule.push(RenderStep::Render(shape_id));
            true
        } else {
            false
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
        // `reset_canvas` clears Fills/Strokes/Current/etc. but NOT Target.
        // Target retains pixels from the previous frame until each tile's
        // finalize_{bg,content} overwrites its rect. A gather tile whose
        // backdrop samples a region whose tiles haven't yet processed THIS
        // frame ends up reading stale pixels from the last frame — the glass
        // then appears to distort whatever was there before. Force a full
        // clear to guarantee a clean slate. Tiles in the schedule rewrite
        // their own rects, and cached_blit paths still draw cached tile
        // images (stored in a separate texture cache, unaffected by this
        // clear).
        self.surfaces
            .canvas(SurfaceId::Target)
            .clear(self.background_color);

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

    /// The main render loop. Walks the pre-computed schedule where every
    /// band is bracketed by a `SetTileBand` (setup) and a `FinalizeBand`
    /// (teardown) step. Because `FinalizeBand.kind` is decided at build
    /// time, nothing about the finalize depends on state that a yield
    /// could drop.
    ///
    /// Per band:
    /// - `SetTileBand` sets up the tile context, then either clears Current
    ///   (is_first, or cached_blit fast-path), or restores Current from
    ///   the interband cache (non-first band).
    /// - `Enter`/`Render`/`Exit` steps render the band's shapes into
    ///   Current.
    /// - `FinalizeBand` executes the precomputed finalize action:
    ///   * `Intermediate` — composite Current→Target (so peer gather
    ///     bands see it) + snapshot Current into interband cache.
    ///   * `LastContent` — apply Current to final canvas + drop interband.
    ///   * `LastBg` — direct bg draw on Target + drop interband.
    fn run_schedule(
        &mut self,
        tree: ShapesPoolRef,
        timestamp: i32,
        can_yield: bool,
    ) -> Result<()> {
        let mut iteration = 0;

        while let Some(step) = self.tile_grid.next() {
            match step {
                RenderStep::SetTileBand {
                    tile,
                    band_index,
                    is_first,
                    is_last,
                } => {
                    self.update_render_context(tile);

                    if is_first {
                        // First visit to this tile. Check for a fully cached
                        // tile surface — the fast path — before clearing.
                        // The cached path is only valid for tiles with a
                        // single band AND no gather at the band head.
                        // Gather tiles' output depends on the current
                        // frame's Target backdrop, so a cached image from
                        // a previous frame is inherently stale.
                        let tile_bands = self.tile_grid.bands.get(&tile);
                        let tile_band_count = tile_bands.map(|b| b.len()).unwrap_or(1);
                        let band_has_gather_head = tile_bands
                            .and_then(|b| b.first())
                            .is_some_and(|b| b.gather_at_head.is_some());
                        if tile_band_count == 1
                            && !band_has_gather_head
                            && self.surfaces.has_cached_tile_surface(tile)
                        {
                            let rect = self.get_current_tile_bounds()?;
                            self.surfaces.draw_cached_tile_surface(
                                tile,
                                rect,
                                self.background_color,
                            );
                            self.current_tile = None;
                            // Blit already drew to final — skip this tile's
                            // FinalizeBand step by advancing to the next
                            // SetTileBand (or end of schedule).
                            self.tile_grid.skip_to_next_tile();
                            continue;
                        }

                        self.surfaces
                            .canvas(SurfaceId::Current)
                            .clear(self.background_color);

                    } else {
                        // Non-first band: restore Current from the snapshot
                        // left by the previous visit to this same tile.
                        // Clear first so the restore lands on a clean slate
                        // (margins outside the content region stay bg).
                        self.surfaces
                            .canvas(SurfaceId::Current)
                            .clear(self.background_color);
                        let restored = self.surfaces.restore_current_from_interband(tile);
                        debug_assert!(
                            restored,
                            "run_schedule: non-first band visit to {:?} without a snapshot",
                            tile
                        );
                    }
                }

                RenderStep::FinalizeBand { tile, kind } => {
                    // The SetTileBand that opened this band already set
                    // current_tile; if it was cleared (cached_blit path),
                    // skip_to_next_tile would have hopped past us. So in
                    // any well-formed schedule, current_tile == Some(tile)
                    // here.
                    debug_assert_eq!(
                        self.current_tile,
                        Some(tile),
                        "FinalizeBand {:?} arrived with current_tile={:?}",
                        tile, self.current_tile
                    );
                    let tile_rect = self.get_current_tile_bounds()?;
                    match kind {
                        FinalizeKind::Intermediate => {
                            self.surfaces.composite_current_to_target(
                                tile_rect,
                                self.background_color,
                            );
                            self.surfaces.snapshot_current_for_interband(tile);
                        }
                        FinalizeKind::LastContent => {
                            self.apply_render_to_final_canvas(tile_rect)?;
                            self.surfaces.drop_interband(tile);
                        }
                        FinalizeKind::LastBg => {
                            let bg = self.background_color;
                            self.surfaces.apply_mut(SurfaceId::Target as u32, |s| {
                                let mut paint = skia::Paint::default();
                                paint.set_color(bg);
                                s.canvas().draw_rect(tile_rect, &paint);
                            });
                            self.surfaces.drop_interband(tile);
                        }
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

                    let scale = self.get_scale();

                    // Visibility check against current tile. Use extrect
                    // (not selrect) so scatter shapes whose output reaches
                    // past selrect into this neighbour tile still fire
                    // their Render step here and blit their slice.
                    let extrect = element.extrect(tree, scale);
                    if !extrect.intersects(self.render_area_with_margins) {
                        continue;
                    }

                    // Is this a scatter shape (visible texture with non-zero
                    // radius)? A scatter shape's displacement sample kernel
                    // crosses tile boundaries, so per-tile save_layer
                    // filtering produces rectangular seams. Instead we render
                    // the shape once into an offscreen scratch sized to its
                    // extrect, apply displacement there, cache the image, and
                    // each tile blits the slice that falls inside its rect.
                    let is_scatter = element
                        .texture
                        .as_ref()
                        .is_some_and(|t| !t.hidden && t.radius > 0.0);

                    if is_scatter {
                        // Build the per-frame cache on first tile visit.
                        if !self.surfaces.has_scatter_output(id) {
                            // If the shape also has glass, snapshot Target
                            // first so the scratch-pass refraction samples
                            // real world content (not the empty scratch,
                            // which would emit black). This mirrors the
                            // root-level gather handling in the non-scatter
                            // branch below.
                            let glass_backdrop = if element
                                .glass
                                .as_ref()
                                .is_some_and(|g| !g.hidden)
                            {
                                let tile_rect = self.get_current_tile_bounds()?;
                                let bg_color = self.background_color;
                                self.surfaces
                                    .composite_current_to_target(tile_rect, bg_color);
                                self.flush_and_submit();
                                Some(
                                    self.surfaces
                                        .get_or_snapshot_glass_backdrop(id, SurfaceId::Target),
                                )
                            } else {
                                None
                            };

                            if let Some((img, clipped_extrect)) =
                                crate::render::texture::render_and_filter_to_image(
                                    self,
                                    element,
                                    tree,
                                    glass_backdrop,
                                )
                            {
                                self.surfaces
                                    .insert_scatter_output(id, img, clipped_extrect);
                            }
                        }

                        if let Some((img, clipped_extrect)) = self
                            .surfaces
                            .get_scatter_output(id)
                            .map(|(i, r)| (i.clone(), *r))
                        {
                            // `dst = clipped_extrect` (not the full
                            // `extrect`) because the scratch only covers
                            // the viewport-clipped region.
                            let translation = self
                                .surfaces
                                .get_render_context_translation(self.render_area, scale);
                            let tile_world = self.render_area;
                            let skip_shadows = self.options.is_fast_mode();
                            let has_inner_shadows = !skip_shadows
                                && element.inner_shadows_visible().next().is_some();

                            let canvas =
                                self.surfaces.canvas_and_mark_dirty(SurfaceId::Current);
                            canvas.save();
                            canvas.scale((scale, scale));
                            canvas.translate(translation);
                            canvas.clip_rect(tile_world, skia::ClipOp::Intersect, true);

                            let src = skia::Rect::from_xywh(
                                0.0,
                                0.0,
                                img.width() as f32,
                                img.height() as f32,
                            );
                            let src_constraint =
                                Some((&src, skia::canvas::SrcRectConstraint::Strict));

                            // Drop shadows — behind the silhouette. The
                            // filter consumes the displaced image's alpha
                            // so the shadow follows the texture's warp.
                            if !skip_shadows {
                                for shadow in element.drop_shadows_visible() {
                                    let Some(filter) = shadow.get_drop_shadow_filter() else {
                                        continue;
                                    };
                                    let mut paint = skia::Paint::default();
                                    paint.set_image_filter(filter);
                                    canvas.draw_image_rect(
                                        &img,
                                        src_constraint,
                                        clipped_extrect,
                                        &paint,
                                    );
                                }
                            }

                            // Silhouette — wrapped in a save_layer when
                            // there are inner shadows so `SrcATop` clips
                            // them to the silhouette's alpha instead of
                            // leaking onto the tile's prior content.
                            if has_inner_shadows {
                                canvas.save_layer(&skia::canvas::SaveLayerRec::default());
                            }

                            canvas.draw_image_rect(
                                &img,
                                src_constraint,
                                clipped_extrect,
                                &skia::Paint::default(),
                            );

                            if has_inner_shadows {
                                for shadow in element.inner_shadows_visible() {
                                    let Some(filter) = shadow.get_inner_shadow_filter()
                                    else {
                                        continue;
                                    };
                                    let mut paint = skia::Paint::default();
                                    paint.set_image_filter(filter);
                                    paint.set_blend_mode(skia::BlendMode::SrcATop);
                                    canvas.draw_image_rect(
                                        &img,
                                        src_constraint,
                                        clipped_extrect,
                                        &paint,
                                    );
                                }
                                canvas.restore();
                            }

                            canvas.restore();
                        }
                    } else {
                        // Non-scatter path: existing per-tile pipeline.

                        // Background blur
                        self.render_background_blur(element, SurfaceId::Current);

                        // Glass effect — root-level gather shapes sample Target
                        // (world-space continuous surface) to avoid per-tile
                        // seams at the refraction/blur sample radius. Nested
                        // glass still samples Current until the container-layer
                        // fix lands.
                        if let Some(glass) = element.glass.as_ref().filter(|g| !g.hidden) {
                            let is_root_level = element
                                .parent_id
                                .is_some_and(|p| p == Uuid::nil());
                            if is_root_level {
                                let tile_rect = self.get_current_tile_bounds()?;
                                let bg_color = self.background_color;
                                self.surfaces.composite_current_to_target(tile_rect, bg_color);
                                self.flush_and_submit();
                                let backdrop_image = self
                                    .surfaces
                                    .get_or_snapshot_glass_backdrop(id, SurfaceId::Target);
                                crate::render::glass::render_glass_with_backdrop_image(
                                    self,
                                    element,
                                    glass,
                                    SurfaceId::Current,
                                    SurfaceId::Target,
                                    Some(backdrop_image),
                                );
                            } else {
                                crate::render::glass::render_glass(
                                    self,
                                    element,
                                    glass,
                                    SurfaceId::Current,
                                );
                            }
                        }

                        // Drop shadows must land BEFORE fills/strokes on
                        // Current; the composite step below overwrites.
                        // Text shapes emit drop shadows via the paragraph
                        // image filter inside `render_shape`.
                        let skip_shadows = self.options.is_fast_mode();
                        if !skip_shadows && !matches!(element.shape_type, Type::Text(_)) {
                            let translation = self
                                .surfaces
                                .get_render_context_translation(self.render_area, scale);
                            let node_render_state =
                                crate::render::NodeRenderState::leaf(id);
                            let mut extrect_cache: Option<skia::Rect> = None;
                            self.render_element_drop_shadows_and_composite(
                                element,
                                tree,
                                &mut extrect_cache,
                                None,
                                scale,
                                translation,
                                &node_render_state,
                                SurfaceId::Current,
                            )?;
                        }

                        self.render_shape(
                            element,
                            None,
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

                        self.apply_drawing_to_render_canvas(Some(element), SurfaceId::Current);
                    }


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

        // With explicit FinalizeBand steps, a well-formed schedule always
        // ends on a FinalizeBand (or on a cached_blit that already drew to
        // final). No trailing finalize needed.

        // Clear any stale inter-band snapshots so they don't leak into
        // the next frame's run_schedule invocation.
        self.surfaces.clear_interband_cache();
        // Same for per-gather-shape backdrop snapshots.
        self.surfaces.clear_glass_backdrop_cache();
        // Same for per-scatter-shape displaced-output snapshots.
        self.surfaces.clear_scatter_output_cache();

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

        // Add to new tiles. paint_order uses 0 as placeholder — callers of
        // update_shape_tiles trigger a renumber before the dep graph rebuilds.
        for tile in (rsx..=rex).flat_map(|x| (rsy..=rey).map(move |y| Tile::from(x, y))) {
            self.tile_grid.add_shape_at(
                tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    paint_order: 0,
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

        // paint_order placeholder — renumbered by the next full/touched pass.
        for tile in &added {
            self.tile_grid.add_shape_at(
                *tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    paint_order: 0,
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

        // paint_order placeholder — renumbered by the next full/touched pass.
        for tile in (rsx..=rex).flat_map(|x| (rsy..=rey).map(move |y| Tile::from(x, y))) {
            self.tile_grid.add_shape_at(
                tile,
                ShapeEntry {
                    id: shape.id,
                    z_index,
                    paint_order: 0,
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

        // Also invalidate any gather tile whose sample region overlaps the
        // changed tiles. Without this, a non-gather shape moving inside a
        // glass/background-blur sample region leaves the gather's cached
        // backdrop stale.
        let gather_affected =
            self.tile_grid
                .gather_tiles_affected_by(&affected, tree, scale);

        for tile in affected {
            self.remove_cached_tile(tile);
        }
        for tile in gather_affected {
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
        let scale = self.get_scale();
        let gather_affected = self.tile_grid.gather_tiles_affected_by(&all_tiles, tree, scale);
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        for tile in gather_affected {
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
        let scale = self.get_scale();
        let gather_affected = self.tile_grid.gather_tiles_affected_by(&all_tiles, tree, scale);
        for tile in all_tiles {
            self.remove_cached_tile(tile);
        }
        for tile in gather_affected {
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
                            paint_order: i as u32,
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
                            paint_order: i as u32,
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
                            paint_order: i as u32,
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
                            paint_order: i as u32,
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
                                paint_order: i as u32,
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
                            paint_order: i as u32,
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
                            paint_order: 500,
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
                            paint_order: i as u32,
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
                            paint_order: 5000 + i as u32,
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

        // Populate bands (1 band per tile — no gather-barrier splits for the
        // synthetic setup below; tests exercise that path separately).
        for (tile, entries) in grid.grid.clone().iter() {
            if entries.is_empty() {
                continue;
            }
            let min = entries.iter().map(|e| e.paint_order).min().unwrap_or(0);
            let max = entries.iter().map(|e| e.paint_order).max().unwrap_or(0);
            grid.bands.insert(
                *tile,
                vec![Band {
                    shapes: entries.iter().map(|e| e.id).collect(),
                    min_paint_order: min,
                    max_paint_order: max,
                    gather_at_head: None,
                }],
            );
        }

        // Build deps manually for benchmark. Keep edges one-directional
        // (dep tile lexicographically less than source tile) so the graph is
        // acyclic — topological_sort's debug_assert requires that.
        let iterations = 100;
        let start = Instant::now();
        for _ in 0..iterations {
            let mut deps: HashMap<BandKey, HashSet<BandKey>> = HashMap::new();
            for (tile, entries) in &grid.grid {
                for entry in entries {
                    if entry.has_gather {
                        for dx in -2..=2 {
                            for dy in -2..=2 {
                                if dx == 0 && dy == 0 {
                                    continue;
                                }
                                let dep = Tile::from(tile.x() + dx, tile.y() + dy);
                                if grid.grid.contains_key(&dep)
                                    && (dep.y(), dep.x()) < (tile.y(), tile.x())
                                {
                                    deps.entry(BandKey::new(*tile, 0))
                                        .or_default()
                                        .insert(BandKey::new(dep, 0));
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

    // ── End-to-end rebuild benches (exercise compute_bands + build_schedule) ──

    fn end_to_end_rebuild(n_shapes: usize, n_gathers: usize, label: &str) {
        use crate::shapes::GlassEffect;
        use crate::state::ShapesPool;
        use crate::view::Viewbox;

        let scale = 1.0;
        let mut pool = ShapesPool::new();
        pool.add_shape(Uuid::nil());

        // Spread non-gather shapes across a tile grid. 100x(n/100) layout.
        for i in 0..n_shapes {
            let id = Uuid::from_u64_pair(1, i as u64);
            let x = (i % 100) as f32 * 60.0;
            let y = (i / 100) as f32 * 60.0;
            let shape = pool.add_shape(id);
            shape.id = id;
            shape.parent_id = Some(Uuid::nil());
            shape.selrect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let root = pool.get_mut(&Uuid::nil()).unwrap();
            root.children.push(id);
        }

        // Sprinkle n_gathers glass shapes, each covering ~1000px square
        // (≈ 2x2 tiles at scale 1).
        for i in 0..n_gathers {
            let id = Uuid::from_u64_pair(2, i as u64);
            let x = (i * 300) as f32;
            let y = (i * 300) as f32;
            let shape = pool.add_shape(id);
            shape.id = id;
            shape.parent_id = Some(Uuid::nil());
            shape.selrect = skia::Rect::from_xywh(x, y, 1000.0, 1000.0);
            shape.glass = Some(GlassEffect {
                surface_type: 0,
                bezel_width: 10.0,
                glass_thickness: 1.0,
                refractive_index: 1.0,
                specular_angle: 0.0,
                specular_opacity: 0.0,
                specular_saturation: 0.0,
                chromatic_aberration: 0.0,
                splay: 0.0,
                tilt_angle: 0.0,
                edge_boost: 0.0,
                zoom: 1.0,
                blur: 50.0,
                frost: 0.0,
                hidden: false,
            });
            let root = pool.get_mut(&Uuid::nil()).unwrap();
            root.children.push(id);
        }

        let viewbox = Viewbox::new(6400.0, 6400.0);
        let tv = TileViewbox::new_with_interest(viewbox, 1, scale);

        let iterations = 20;
        let mut grid = TileGrid::new();
        // Warmup
        grid.rebuild(&pool, &tv, scale);

        let start = Instant::now();
        for _ in 0..iterations {
            grid.rebuild(&pool, &tv, scale);
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] E2E rebuild {}: {:.3}ms/rebuild (schedule_len={}, total_bands={})",
            label,
            elapsed.as_secs_f64() * 1000.0 / iterations as f64,
            grid.schedule.len(),
            grid.bands.values().map(|v| v.len()).sum::<usize>(),
        );
    }

    #[test]
    fn bench_rebuild_e2e_no_gather() {
        end_to_end_rebuild(1_000, 0, "1k shapes, 0 gathers");
    }

    #[test]
    fn bench_rebuild_e2e_few_gathers() {
        end_to_end_rebuild(1_000, 5, "1k shapes, 5 gathers");
    }

    #[test]
    fn bench_rebuild_e2e_many_gathers() {
        end_to_end_rebuild(1_000, 20, "1k shapes, 20 gathers");
    }
}

// ── Correctness tests for paint_order + dep-graph scheduling ────────────

#[cfg(test)]
mod scheduling_tests {
    use super::*;
    use crate::shapes::GlassEffect;
    use crate::state::ShapesPool;
    use crate::view::Viewbox;

    fn uid(n: u64) -> Uuid {
        Uuid::from_u64_pair(0, n)
    }

    /// Build a minimal pool with a root (Uuid::nil()) in place.
    fn new_pool() -> ShapesPool {
        let mut pool = ShapesPool::new();
        pool.add_shape(Uuid::nil());
        pool
    }

    /// Add a shape to the pool as a child of `parent_id` with the given
    /// selrect. Appends `id` to the parent's `children` vec, respecting
    /// bottom-first paint order.
    fn add_leaf(pool: &mut ShapesPool, id: Uuid, parent_id: Uuid, rect: skia::Rect) {
        let shape = pool.add_shape(id);
        shape.id = id;
        shape.parent_id = Some(parent_id);
        shape.selrect = rect;

        let parent = pool.get_mut(&parent_id).expect("parent must exist");
        parent.children.push(id);
    }

    /// As `add_leaf` but also attaches a default non-hidden `GlassEffect`.
    fn add_glass(pool: &mut ShapesPool, id: Uuid, parent_id: Uuid, rect: skia::Rect) {
        add_leaf(pool, id, parent_id, rect);
        let s = pool.get_mut(&id).unwrap();
        s.glass = Some(GlassEffect {
            surface_type: 0,
            bezel_width: 10.0,
            glass_thickness: 1.0,
            refractive_index: 1.0,
            specular_angle: 0.0,
            specular_opacity: 0.0,
            specular_saturation: 0.0,
            chromatic_aberration: 0.0,
            splay: 0.0,
            tilt_angle: 0.0,
            edge_boost: 0.0,
            zoom: 1.0,
            blur: 50.0,
            frost: 0.0,
            hidden: false,
        });
    }

    fn make_tile_viewbox(scale: f32) -> TileViewbox {
        let viewbox = Viewbox::new(1920.0, 1080.0);
        TileViewbox::new_with_interest(viewbox, 1, scale)
    }

    /// Find the first ShapeEntry for `id` anywhere in the grid.
    fn entry_for<'a>(grid: &'a TileGrid, id: Uuid) -> Option<&'a ShapeEntry> {
        for entries in grid.grid.values() {
            for e in entries {
                if e.id == id {
                    return Some(e);
                }
            }
        }
        None
    }

    /// Position of the first `SetTileBand` step targeting `tile` in the flat
    /// schedule.
    fn set_tile_pos(schedule: &[RenderStep], tile: Tile) -> Option<usize> {
        schedule.iter().position(|s| matches!(s, RenderStep::SetTileBand { tile: t, .. } if *t == tile))
    }

    // ── paint_order assignment ───────────────────────────────────────

    #[test]
    fn paint_order_is_depth_first_bottom_first() {
        // Tree: root → [leaf_a, container → [child_x, child_y], leaf_b]
        // Paint order: leaf_a (0) → container (1) → child_x (2) → child_y (3) → leaf_b (4)
        let scale = 1.0;
        let mut pool = new_pool();
        let leaf_a = uid(10);
        let container = uid(20);
        let child_x = uid(21);
        let child_y = uid(22);
        let leaf_b = uid(30);

        add_leaf(&mut pool, leaf_a, Uuid::nil(), skia::Rect::from_xywh(100.0, 100.0, 100.0, 100.0));
        add_leaf(&mut pool, container, Uuid::nil(), skia::Rect::from_xywh(300.0, 100.0, 300.0, 300.0));
        // Mark container as a Group so it's recursive.
        {
            let c = pool.get_mut(&container).unwrap();
            c.shape_type = crate::shapes::Type::Group(crate::shapes::Group { masked: false });
        }
        add_leaf(&mut pool, child_x, container, skia::Rect::from_xywh(300.0, 100.0, 100.0, 100.0));
        add_leaf(&mut pool, child_y, container, skia::Rect::from_xywh(450.0, 100.0, 100.0, 100.0));
        add_leaf(&mut pool, leaf_b, Uuid::nil(), skia::Rect::from_xywh(700.0, 100.0, 100.0, 100.0));

        let mut grid = TileGrid::new();
        let tv = make_tile_viewbox(scale);
        grid.rebuild(&pool, &tv, scale);

        let po = |id: Uuid| entry_for(&grid, id).map(|e| e.paint_order).unwrap_or(u32::MAX);

        let po_a = po(leaf_a);
        let po_ctr = po(container);
        let po_x = po(child_x);
        let po_y = po(child_y);
        let po_b = po(leaf_b);

        assert_eq!(po_a, 0, "leaf_a is painted first");
        assert!(po_ctr > po_a && po_ctr < po_x, "container sits between leaf_a and its children (got {po_ctr})");
        assert!(po_x < po_y, "child_x painted before child_y (got {po_x} vs {po_y})");
        assert!(po_b > po_y, "leaf_b painted after container's children");
    }

    // ── Band model + band-aware scheduling ─────────────────────────

    /// Count `SetTileBand` steps for a specific tile in the flat schedule.
    fn band_steps_for(schedule: &[RenderStep], tile: Tile) -> Vec<(u32, bool, bool)> {
        schedule
            .iter()
            .filter_map(|s| match s {
                RenderStep::SetTileBand {
                    tile: t,
                    band_index,
                    is_first,
                    is_last,
                } if *t == tile => Some((*band_index, *is_first, *is_last)),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn bands_far_tile_not_split_by_distant_gather() {
        // Locality check: a gather near the origin must NOT force tiles far
        // from it (outside its sample region) into multiple bands.
        let scale = 1.0;
        let mut pool = new_pool();
        let glass = uid(1);
        let far = uid(2);
        // Small glass around origin — sample region stays close.
        add_glass(&mut pool, glass, Uuid::nil(), skia::Rect::from_xywh(50.0, 50.0, 100.0, 100.0));
        // Shape far outside glass's sample region. Tile ~ (15, 15) at scale 1
        // (tile size ≈ 512).
        add_leaf(&mut pool, far, Uuid::nil(), skia::Rect::from_xywh(7800.0, 7800.0, 100.0, 100.0));

        let mut grid = TileGrid::new();
        // Custom viewbox wide enough to include the far shape.
        let viewbox = crate::view::Viewbox::new(16384.0, 16384.0);
        let tv = TileViewbox::new_with_interest(viewbox, 1, scale);
        grid.rebuild(&pool, &tv, scale);

        let far_tiles: Vec<Tile> = grid.index.get(&far).unwrap().iter().copied().collect();
        assert!(!far_tiles.is_empty(), "far shape must be indexed");
        for t in &far_tiles {
            let bands = grid.bands.get(t).expect("tile must have bands");
            assert_eq!(
                bands.len(),
                1,
                "far tile {:?} must not be split by distant gather; got {} bands",
                t,
                bands.len()
            );
        }
    }

    #[test]
    fn peer_glass_regression_no_tile_dropped() {
        // Primary regression for the original bug: a single glass shape
        // spans multiple tiles with a below-shape in all of them. Under the
        // old tile-atomic scheduler this produced a cycle; Kahn dropped the
        // cycle-bound tiles silently. Under the band model it must resolve
        // to a strict DAG with every BandKey present in the final schedule.
        let scale = 1.0;
        let mut pool = new_pool();
        let backdrop = uid(1);
        let glass = uid(2);
        // Backdrop spans roughly the same tiles as glass so both sit in
        // each tile.
        add_leaf(
            &mut pool,
            backdrop,
            Uuid::nil(),
            skia::Rect::from_xywh(200.0, 200.0, 1400.0, 1400.0),
        );
        // Glass covering tiles (0,0)..(2,2) at scale 1.
        add_glass(
            &mut pool,
            glass,
            Uuid::nil(),
            skia::Rect::from_xywh(200.0, 200.0, 1400.0, 1400.0),
        );

        let mut grid = TileGrid::new();
        let tv = make_tile_viewbox(scale);
        grid.rebuild(&pool, &tv, scale);

        // Every tile that contains the glass shape must emit ≥ 1 band that
        // has the gather at its head. More importantly, no glass-tile may
        // be missing from the schedule.
        let glass_tiles: Vec<Tile> = grid.index.get(&glass).unwrap().iter().copied().collect();
        assert!(!glass_tiles.is_empty());
        for gt in &glass_tiles {
            let steps = band_steps_for(&grid.schedule, *gt);
            assert!(
                !steps.is_empty(),
                "glass tile {:?} must appear in the schedule (would be missing under old scheduler): \
                 schedule_len={} bands_for_tile={:?}",
                gt,
                grid.schedule.len(),
                grid.bands.get(gt)
            );
            // A glass tile always has ≥ 2 bands (below + gather-at-head).
            assert!(
                steps.len() >= 2,
                "glass tile {:?} must have ≥ 2 bands; got {:?}",
                gt,
                steps
            );
        }
    }

    #[test]
    fn schedule_is_complete_every_band_scheduled_exactly_once() {
        // Every BandKey produced by `compute_bands` must appear in the
        // schedule exactly once (no duplicates, no drops). The schedule may
        // ALSO contain synthetic empty-tile SetTileBands for spiral tiles
        // that have no shapes — those are needed to clear Target at those
        // tile rects so previous-frame pixels don't linger.
        let scale = 1.0;
        let mut pool = new_pool();
        let backdrop = uid(1);
        let glass = uid(2);
        add_leaf(&mut pool, backdrop, Uuid::nil(), skia::Rect::from_xywh(100.0, 100.0, 100.0, 100.0));
        add_glass(&mut pool, glass, Uuid::nil(), skia::Rect::from_xywh(600.0, 600.0, 500.0, 500.0));

        let mut grid = TileGrid::new();
        let tv = make_tile_viewbox(scale);
        grid.rebuild(&pool, &tv, scale);

        let mut expected: HashSet<BandKey> = HashSet::new();
        for (tile, bands) in &grid.bands {
            for idx in 0..bands.len() {
                expected.insert(BandKey::new(*tile, idx as u32));
            }
        }

        let mut got: HashSet<BandKey> = HashSet::new();
        for step in &grid.schedule {
            if let RenderStep::SetTileBand { tile, band_index, .. } = step {
                let key = BandKey::new(*tile, *band_index);
                assert!(got.insert(key), "duplicate SetTileBand for {:?}", key);
            }
        }
        for key in &expected {
            assert!(
                got.contains(key),
                "schedule missing required BandKey {:?}",
                key
            );
        }
    }

    #[test]
    fn gather_band_depends_on_below_shape_in_different_tile() {
        // A below-shape sits in a tile NOT containing the gather itself. The
        // gather's sample region reaches into the below-shape's tile. The
        // schedule must ensure the below-shape tile's band 0 runs BEFORE any
        // gather band that samples it, so Target has the below content when
        // the gather reads its backdrop.
        let scale = 1.0;
        let mut pool = new_pool();
        let below = uid(1); // po=0 — far from the gather
        let glass = uid(2); // po=1 — gather with large sample radius

        // Below shape at tile (0,0) area.
        add_leaf(&mut pool, below, Uuid::nil(), skia::Rect::from_xywh(100.0, 100.0, 100.0, 100.0));
        // Glass at tile (1,1)-(2,2). With default glass params (blur=50,
        // thickness=1) the sample expand is large enough to include (0,0).
        add_glass(&mut pool, glass, Uuid::nil(), skia::Rect::from_xywh(600.0, 600.0, 500.0, 500.0));

        let mut grid = TileGrid::new();
        let tv = make_tile_viewbox(scale);
        grid.rebuild(&pool, &tv, scale);

        let below_tile = Tile::from(0, 0);
        let below_pos = set_tile_pos(&grid.schedule, below_tile)
            .expect("below tile (0,0) must appear in the schedule");

        // For every glass tile, locate its gather-head SetTileBand and
        // assert it comes after below_tile's SetTileBand.
        let glass_tiles: Vec<Tile> = grid.index.get(&glass).unwrap().iter().copied().collect();
        for gt in &glass_tiles {
            // Find the gather band (the one whose gather_at_head matches).
            let bands = grid.bands.get(gt).expect("glass tile has bands");
            let gather_band_idx = bands
                .iter()
                .position(|b| b.gather_at_head == Some(glass))
                .expect("glass tile must have a gather-head band");
            let gather_step_pos = grid
                .schedule
                .iter()
                .position(|s| {
                    matches!(
                        s,
                        RenderStep::SetTileBand { tile: t, band_index, .. }
                            if *t == *gt && *band_index == gather_band_idx as u32
                    )
                })
                .expect("gather band must be in schedule");
            assert!(
                below_pos < gather_step_pos,
                "below tile (0,0) [pos {}] must come before glass tile {:?} gather band [pos {}]",
                below_pos, gt, gather_step_pos
            );
        }
    }

    #[test]
    fn non_gather_tile_in_sample_region_split_by_barrier() {
        // A tile that contains NO gather but IS in another gather's sample
        // region must still split at the gather's paint_order, so
        // above-gather shapes don't leak into the gather's backdrop sample.
        let scale = 1.0;
        let mut pool = new_pool();
        let below = uid(1); // po=0
        let glass = uid(2); // po=1
        let above = uid(3); // po=2

        // `below` inside tile (0,0).
        add_leaf(&mut pool, below, Uuid::nil(), skia::Rect::from_xywh(50.0, 50.0, 100.0, 100.0));
        // `glass` with sample region large enough to include (0,0).
        add_glass(&mut pool, glass, Uuid::nil(), skia::Rect::from_xywh(600.0, 600.0, 500.0, 500.0));
        // `above` in tile (0,0), ABOVE glass in paint order.
        add_leaf(&mut pool, above, Uuid::nil(), skia::Rect::from_xywh(160.0, 50.0, 100.0, 100.0));

        let mut grid = TileGrid::new();
        let tv = make_tile_viewbox(scale);
        grid.rebuild(&pool, &tv, scale);

        let tile_00 = Tile::from(0, 0);
        let bands = grid.bands.get(&tile_00).expect("tile (0,0) has shapes");

        // Tile (0,0) has no gather of its own, so we should only see a
        // barrier split IF (0,0) is in glass's sample region. It is (glass
        // with blur=50 + thickness=1 has a large sample expand that reaches
        // (0,0)), so expect exactly 2 bands: [below], [above].
        assert_eq!(
            bands.len(),
            2,
            "tile (0,0) must be split into 2 bands by glass's barrier; got {}: {:?}",
            bands.len(),
            bands
        );

        let po_below = entry_for(&grid, below).unwrap().paint_order;
        let po_above = entry_for(&grid, above).unwrap().paint_order;
        assert!(bands[0].shapes.contains(&below));
        assert!(bands[1].shapes.contains(&above));
        assert!(bands[0].max_paint_order == po_below);
        assert!(bands[1].min_paint_order == po_above);

        // Glass's band deps must include (0,0)'s band 0 but NOT band 1.
        let tile_size = tiles::get_tile_size(scale);
        let deps = grid.build_dependency_graph(&pool, tile_size, &tv.interest_rect, scale);

        let any_on_00_band0 = deps.iter().any(|(_, ds)| {
            ds.iter().any(|d| d.tile == tile_00 && d.band_index == 0)
        });
        let any_on_00_band1 = deps.iter().any(|(_, ds)| {
            ds.iter().any(|d| d.tile == tile_00 && d.band_index == 1)
        });
        assert!(any_on_00_band0, "some band must depend on (0,0)/0 (below-glass)");
        assert!(!any_on_00_band1, "no band may depend on (0,0)/1 (above-glass leak!)");
    }
}
