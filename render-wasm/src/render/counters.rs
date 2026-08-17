//! Exact render counters for before/after comparisons. Read from the browser
//! through the `perf_counter_*` exports in `main.rs`; [`NAMES`] is mirrored by
//! index in `playwright/ui/render-wasm-specs/render-budget-perf.spec.js`.

#[repr(usize)]
#[derive(Copy, Clone)]
#[allow(dead_code)]
pub enum Counter {
    RenderLoopStarts = 0,
    RenderLoopContinues,
    PartialYields,
    TilesPainted,
    TilesCacheHit,
    TilesEmptySkipped,
    TilesInvalidated,
    TileCacheWipes,
    TilesDiscardedInflight,
    WalkerVisits,
    WalkerCulled,
    ShapePaints,
    ShapePaintsDirect,
    SurfaceStackComposites,
    SurfaceStackDrawPx,
    SurfaceStackClearPx,
    ParagraphBuilds,
    TextLayouts,
    DocAtlasWrites,
    TileAtlasWrites,
    /// Unused since the Cache blit was removed; kept so indices stay stable.
    CacheSurfaceWrites,
    TileAtlasSnapshots,
    TileAtlasSnapshotPx,
    FramePresents,
    CropEntriesBuilt,
    CropBlits,
    CropRejected,
    ShapeTileUpdates,
}

pub const COUNTER_COUNT: usize = 28;

pub const NAMES: [&str; COUNTER_COUNT] = [
    "render_loop_starts",
    "render_loop_continues",
    "partial_yields",
    "tiles_painted",
    "tiles_cache_hit",
    "tiles_empty_skipped",
    "tiles_invalidated",
    "tile_cache_wipes",
    "tiles_discarded_inflight",
    "walker_visits",
    "walker_culled",
    "shape_paints",
    "shape_paints_direct",
    "surface_stack_composites",
    "surface_stack_draw_px",
    "surface_stack_clear_px",
    "paragraph_builds",
    "text_layouts",
    "doc_atlas_writes",
    "tile_atlas_writes",
    "cache_surface_writes",
    "tile_atlas_snapshots",
    "tile_atlas_snapshot_px",
    "frame_presents",
    "crop_entries_built",
    "crop_blits",
    "crop_rejected",
    "shape_tile_updates",
];

static mut COUNTERS: [f64; COUNTER_COUNT] = [0.0; COUNTER_COUNT];

/// `f64` so the JS side reads plain numbers; exact past any count we reach.
#[inline(always)]
pub fn add(counter: Counter, n: f64) {
    unsafe {
        COUNTERS[counter as usize] += n;
    }
}

#[inline(always)]
pub fn get(index: usize) -> f64 {
    if index >= COUNTER_COUNT {
        return 0.0;
    }
    unsafe { COUNTERS[index] }
}

pub fn reset() {
    unsafe {
        COUNTERS = [0.0; COUNTER_COUNT];
    }
}

#[macro_export]
macro_rules! count {
    ($counter:expr) => {
        $crate::render::counters::add($counter, 1.0)
    };
    ($counter:expr, $n:expr) => {
        $crate::render::counters::add($counter, $n as f64)
    };
}
