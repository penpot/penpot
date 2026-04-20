use crate::render::Surfaces;
use crate::uuid::Uuid;
use crate::view::Viewbox;
use skia_safe as skia;
use std::collections::{HashMap, HashSet};
#[derive(PartialEq, Eq, Hash, Clone, Copy, Debug)]
pub struct Tile(pub i32, pub i32);

impl Tile {
    pub fn from(x: i32, y: i32) -> Self {
        Tile(x, y)
    }
    pub fn x(&self) -> i32 {
        self.0
    }
    pub fn y(&self) -> i32 {
        self.1
    }
}

#[derive(PartialEq, Eq, Hash, Clone, Copy, Debug)]
pub struct TileRect(pub i32, pub i32, pub i32, pub i32);

impl TileRect {
    pub fn x1(&self) -> i32 {
        self.0
    }

    pub fn y1(&self) -> i32 {
        self.1
    }

    pub fn x2(&self) -> i32 {
        self.2
    }

    pub fn y2(&self) -> i32 {
        self.3
    }

    pub fn width(&self) -> i32 {
        self.x2() - self.x1()
    }

    pub fn height(&self) -> i32 {
        self.y2() - self.y1()
    }

    pub fn center_x(&self) -> i32 {
        self.x1() + self.width() / 2
    }

    pub fn center_y(&self) -> i32 {
        self.y1() + self.height() / 2
    }

    pub fn contains(&self, tile: &Tile) -> bool {
        tile.x() >= self.x1()
            && tile.y() >= self.y1()
            && tile.x() <= self.x2()
            && tile.y() <= self.y2()
    }
}

#[derive(Debug)]
pub struct TileViewbox {
    pub visible_rect: TileRect,
    pub interest_rect: TileRect,
    pub interest: i32,
    pub center: Tile,
}

impl TileViewbox {
    pub fn new_with_interest(viewbox: Viewbox, interest: i32, scale: f32) -> Self {
        Self {
            visible_rect: get_tiles_for_viewbox(viewbox, scale),
            interest_rect: get_tiles_for_viewbox_with_interest(viewbox, interest, scale),
            interest,
            center: get_tile_center_for_viewbox(viewbox, scale),
        }
    }

    pub fn update(&mut self, viewbox: Viewbox, scale: f32) {
        self.visible_rect = get_tiles_for_viewbox(viewbox, scale);
        self.interest_rect = get_tiles_for_viewbox_with_interest(viewbox, self.interest, scale);
        self.center = get_tile_center_for_viewbox(viewbox, scale);
    }

    pub fn is_visible(&self, tile: &Tile) -> bool {
        // TO CHECK self.interest_rect.contains(tile)
        self.visible_rect.contains(tile)
    }
}

pub const TILE_SIZE: f32 = 512.;

pub fn get_tile_dimensions() -> skia::ISize {
    (TILE_SIZE as i32, TILE_SIZE as i32).into()
}

pub fn get_tiles_for_rect(rect: skia::Rect, tile_size: f32) -> TileRect {
    // start
    let sx = (rect.left / tile_size).floor() as i32;
    let sy = (rect.top / tile_size).floor() as i32;
    // end
    let ex = (rect.right / tile_size).floor() as i32;
    let ey = (rect.bottom / tile_size).floor() as i32;
    TileRect(sx, sy, ex, ey)
}

pub fn get_tiles_for_viewbox(viewbox: Viewbox, scale: f32) -> TileRect {
    let tile_size = get_tile_size(scale);
    get_tiles_for_rect(viewbox.area, tile_size)
}

pub fn get_tiles_for_viewbox_with_interest(
    viewbox: Viewbox,
    interest: i32,
    scale: f32,
) -> TileRect {
    let TileRect(sx, sy, ex, ey) = get_tiles_for_viewbox(viewbox, scale);
    TileRect(sx - interest, sy - interest, ex + interest, ey + interest)
}

pub fn get_tile_center_for_viewbox(viewbox: Viewbox, scale: f32) -> Tile {
    let TileRect(sx, sy, ex, ey) = get_tiles_for_viewbox(viewbox, scale);
    Tile((ex - sx) / 2, (ey - sy) / 2)
}

pub fn get_tile_pos(Tile(x, y): Tile, scale: f32) -> (f32, f32) {
    (
        x as f32 * get_tile_size(scale),
        y as f32 * get_tile_size(scale),
    )
}

pub fn get_tile_size(scale: f32) -> f32 {
    1. / scale * TILE_SIZE
}

pub fn get_tile_rect(tile: Tile, scale: f32) -> skia::Rect {
    let (tx, ty) = get_tile_pos(tile, scale);
    let ts = get_tile_size(scale);
    skia::Rect::from_xywh(tx, ty, ts, ts)
}

// This structure is usseful to keep all the shape uuids by shape id.
pub struct TileHashMap {
    grid: HashMap<Tile, HashSet<Uuid>>,
    index: HashMap<Uuid, HashSet<Tile>>,
}

impl TileHashMap {
    pub fn new() -> Self {
        TileHashMap {
            grid: HashMap::new(),
            index: HashMap::new(),
        }
    }

    pub fn get_shapes_at(&mut self, tile: Tile) -> Option<&HashSet<Uuid>> {
        self.grid.get(&tile)
    }

    pub fn remove_shape_at(&mut self, tile: Tile, id: Uuid) {
        if let Some(shapes) = self.grid.get_mut(&tile) {
            shapes.remove(&id);
        }

        if let Some(tiles) = self.index.get_mut(&id) {
            tiles.remove(&tile);
        }
    }

    pub fn get_tiles_of(&mut self, shape_id: Uuid) -> Option<&HashSet<Tile>> {
        self.index.get(&shape_id)
    }

    pub fn add_shape_at(&mut self, tile: Tile, shape_id: Uuid) {
        let tile_set = self.grid.entry(tile).or_default();
        tile_set.insert(shape_id);

        let index_set = self.index.entry(shape_id).or_default();
        index_set.insert(tile);
    }

    pub fn invalidate(&mut self) {
        self.grid.clear();
        self.index.clear();
    }
}

const VIEWPORT_DEFAULT_CAPACITY: usize = 24 * 12;

// This structure keeps the list of tiles that are in the pending list, the
// ones that are going to be rendered.
pub struct PendingTiles {
    pub list: Vec<Tile>,
}

impl PendingTiles {
    pub fn new_empty() -> Self {
        Self {
            list: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
        }
    }

    // Generate tiles in spiral order from center
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

        result.push(Tile(cx, cy));
        while current < total {
            match direction {
                0 => cx += 1,
                1 => cy += 1,
                2 => cx -= 1,
                3 => cy -= 1,
                _ => unreachable!("Invalid direction"),
            }

            result.push(Tile(cx, cy));

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
        result.reverse();
        result
    }

    pub fn update(&mut self, tile_viewbox: &TileViewbox, surfaces: &Surfaces) {
        self.list.clear();

        // Generate spiral for the interest area (viewport + margin)
        let spiral = Self::generate_spiral(&tile_viewbox.interest_rect);

        // Partition tiles into 4 priority groups (highest priority = processed last due to pop()):
        // 1. visible + cached (fastest - just blit from cache)
        // 2. visible + uncached (user sees these, render next)
        // 3. interest + cached (pre-rendered area, blit from cache)
        // 4. interest + uncached (lowest priority - background pre-render)
        let mut visible_cached = Vec::new();
        let mut visible_uncached = Vec::new();
        let mut interest_cached = Vec::new();
        let mut interest_uncached = Vec::new();

        for tile in spiral {
            let is_visible = tile_viewbox.visible_rect.contains(&tile);
            let is_cached = surfaces.has_cached_tile_surface(tile);

            match (is_visible, is_cached) {
                (true, true) => visible_cached.push(tile),
                (true, false) => visible_uncached.push(tile),
                (false, true) => interest_cached.push(tile),
                (false, false) => interest_uncached.push(tile),
            }
        }

        // Build final list with lowest priority first (they get popped last)
        // Order: interest_uncached, interest_cached, visible_uncached, visible_cached
        self.list.extend(interest_uncached);
        self.list.extend(interest_cached);
        self.list.extend(visible_uncached);
        self.list.extend(visible_cached);
    }

    pub fn pop(&mut self) -> Option<Tile> {
        self.list.pop()
    }
}

#[cfg(test)]
mod bench {
    use super::*;
    use std::time::Instant;

    fn make_uuid(n: u64) -> Uuid {
        Uuid::from_u64_pair(0, n)
    }

    fn make_viewbox(width: f32, height: f32, zoom: f32, pan_x: f32, pan_y: f32) -> Viewbox {
        let mut vb = Viewbox::new(width, height);
        vb.set_all(zoom, pan_x, pan_y);
        vb
    }

    // ── Scenario 1: TileHashMap add — bulk insert N shapes ──────────────

    #[test]
    fn bench_tilehashmap_add_1k() {
        tilehashmap_add_n(1_000);
    }

    #[test]
    fn bench_tilehashmap_add_10k() {
        tilehashmap_add_n(10_000);
    }

    #[test]
    fn bench_tilehashmap_add_50k() {
        tilehashmap_add_n(50_000);
    }

    fn tilehashmap_add_n(n: usize) {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);
        let mut map = TileHashMap::new();

        // Shapes distributed across a large canvas (10000x10000 world units)
        let start = Instant::now();
        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = get_tiles_for_rect(rect, tile_size);

            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    map.add_shape_at(Tile::from(tx, ty), id);
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileHashMap add {n} shapes: {:.3}ms ({:.1}µs/shape)",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / n as f64
        );
    }

    // ── Scenario 2: TileHashMap move — remove + re-add (drag hot path) ──

    #[test]
    fn bench_tilehashmap_move_1k() {
        tilehashmap_move_n(1_000);
    }

    #[test]
    fn bench_tilehashmap_move_10k() {
        tilehashmap_move_n(10_000);
    }

    fn tilehashmap_move_n(n: usize) {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);
        let mut map = TileHashMap::new();

        // Setup: insert all shapes
        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    map.add_shape_at(Tile::from(tx, ty), id);
                }
            }
        }

        // Benchmark: move each shape by (10, 10) — remove from old tiles, add to new
        let start = Instant::now();
        for i in 0..n {
            let id = make_uuid(i as u64);
            let old_x = (i % 200) as f32 * 50.0;
            let old_y = (i / 200) as f32 * 50.0;
            let new_x = old_x + 10.0;
            let new_y = old_y + 10.0;

            // Remove from old tiles
            let old_rect = skia::Rect::from_xywh(old_x, old_y, 100.0, 80.0);
            let old_tiles = get_tiles_for_rect(old_rect, tile_size);
            for tx in old_tiles.x1()..=old_tiles.x2() {
                for ty in old_tiles.y1()..=old_tiles.y2() {
                    map.remove_shape_at(Tile::from(tx, ty), id);
                }
            }

            // Add to new tiles
            let new_rect = skia::Rect::from_xywh(new_x, new_y, 100.0, 80.0);
            let new_tiles = get_tiles_for_rect(new_rect, tile_size);
            for tx in new_tiles.x1()..=new_tiles.x2() {
                for ty in new_tiles.y1()..=new_tiles.y2() {
                    map.add_shape_at(Tile::from(tx, ty), id);
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileHashMap move {n} shapes: {:.3}ms ({:.1}µs/shape)",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / n as f64
        );
    }

    // ── Scenario 3: TileHashMap query — shapes_at for visible tiles ─────

    #[test]
    fn bench_tilehashmap_query_viewport() {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);
        let n = 10_000;
        let mut map = TileHashMap::new();

        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    map.add_shape_at(Tile::from(tx, ty), id);
                }
            }
        }

        // Query all tiles in a 1920x1080 viewport
        let viewport = skia::Rect::from_xywh(500.0, 500.0, 1920.0, 1080.0);
        let visible_tiles = get_tiles_for_rect(viewport, tile_size);
        let tile_count =
            (visible_tiles.width() + 1) as usize * (visible_tiles.height() + 1) as usize;

        let iterations = 100;
        let start = Instant::now();
        let mut total_shapes = 0usize;
        for _ in 0..iterations {
            for tx in visible_tiles.x1()..=visible_tiles.x2() {
                for ty in visible_tiles.y1()..=visible_tiles.y2() {
                    if let Some(shapes) = map.get_shapes_at(Tile::from(tx, ty)) {
                        total_shapes += shapes.len();
                    }
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] TileHashMap query viewport ({tile_count} tiles, {n} shapes): \
             {:.3}ms/query, {total_shapes} shape refs over {iterations} iterations",
            elapsed.as_secs_f64() * 1000.0 / iterations as f64
        );
    }

    // ── Scenario 4: Spiral generation ───────────────────────────────────

    #[test]
    fn bench_spiral_generation() {
        let sizes = [(5, 4), (10, 8), (20, 15), (40, 30)];

        for (w, h) in sizes {
            let rect = TileRect(0, 0, w, h);
            let total = (w + 1) * (h + 1);

            let iterations = 1000;
            let start = Instant::now();
            for _ in 0..iterations {
                let _ = PendingTiles::generate_spiral(&rect);
            }
            let elapsed = start.elapsed();
            println!(
                "[bench] Spiral {w}x{h} ({total} tiles): {:.3}ms/gen",
                elapsed.as_secs_f64() * 1000.0 / iterations as f64
            );
        }
    }

    // ── Scenario 5: get_tiles_for_rect throughput ───────────────────────

    #[test]
    fn bench_tiles_for_rect() {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);
        let n = 100_000;

        let start = Instant::now();
        for i in 0..n {
            let x = (i % 1000) as f32 * 10.0;
            let y = (i / 1000) as f32 * 10.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let _ = get_tiles_for_rect(rect, tile_size);
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] get_tiles_for_rect {n} calls: {:.3}ms ({:.0}ns/call)",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000_000.0 / n as f64
        );
    }

    // ── Scenario 6: Full tile rebuild (zoom level crossing) ─────────────

    #[test]
    fn bench_full_rebuild_10k() {
        full_rebuild_n(10_000);
    }

    #[test]
    fn bench_full_rebuild_50k() {
        full_rebuild_n(50_000);
    }

    fn full_rebuild_n(n: usize) {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);

        // Pre-compute shape rects
        let rects: Vec<_> = (0..n)
            .map(|i| {
                let x = (i % 200) as f32 * 50.0;
                let y = (i / 200) as f32 * 50.0;
                (make_uuid(i as u64), skia::Rect::from_xywh(x, y, 100.0, 80.0))
            })
            .collect();

        let iterations = 10;
        let start = Instant::now();
        for _ in 0..iterations {
            let mut map = TileHashMap::new();
            for (id, rect) in &rects {
                let tile_rect = get_tiles_for_rect(*rect, tile_size);
                for tx in tile_rect.x1()..=tile_rect.x2() {
                    for ty in tile_rect.y1()..=tile_rect.y2() {
                        map.add_shape_at(Tile::from(tx, ty), *id);
                    }
                }
            }
            map.invalidate();
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] Full rebuild {n} shapes: {:.3}ms/rebuild",
            elapsed.as_secs_f64() * 1000.0 / iterations as f64
        );
    }

    // ── Scenario 7: Single shape move (drag frame latency) ──────────────

    #[test]
    fn bench_single_shape_drag_latency() {
        let scale = 1.0;
        let tile_size = get_tile_size(scale);
        let n = 10_000;
        let mut map = TileHashMap::new();

        // Setup scene
        for i in 0..n {
            let id = make_uuid(i as u64);
            let x = (i % 200) as f32 * 50.0;
            let y = (i / 200) as f32 * 50.0;
            let rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let tile_rect = get_tiles_for_rect(rect, tile_size);
            for tx in tile_rect.x1()..=tile_rect.x2() {
                for ty in tile_rect.y1()..=tile_rect.y2() {
                    map.add_shape_at(Tile::from(tx, ty), id);
                }
            }
        }

        // Benchmark: drag ONE shape 1000 times (simulating 1000 mouse events)
        let drag_id = make_uuid(500);
        let iterations = 1000;
        let mut x = 250.0_f32 * 50.0;
        let y = 2.0_f32 * 50.0;

        let start = Instant::now();
        for _ in 0..iterations {
            // Remove from old
            let old_rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let old_tiles = get_tiles_for_rect(old_rect, tile_size);
            for tx in old_tiles.x1()..=old_tiles.x2() {
                for ty in old_tiles.y1()..=old_tiles.y2() {
                    map.remove_shape_at(Tile::from(tx, ty), drag_id);
                }
            }
            x += 2.0; // small drag increment
            // Add to new
            let new_rect = skia::Rect::from_xywh(x, y, 100.0, 80.0);
            let new_tiles = get_tiles_for_rect(new_rect, tile_size);
            for tx in new_tiles.x1()..=new_tiles.x2() {
                for ty in new_tiles.y1()..=new_tiles.y2() {
                    map.add_shape_at(Tile::from(tx, ty), drag_id);
                }
            }
        }
        let elapsed = start.elapsed();
        println!(
            "[bench] Single shape drag ({n} scene, {iterations} moves): \
             {:.3}ms total, {:.1}µs/move",
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / iterations as f64
        );
    }
}
