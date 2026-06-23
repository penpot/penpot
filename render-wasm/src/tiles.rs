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

    #[inline(always)]
    pub fn x(&self) -> i32 {
        self.0
    }

    #[inline(always)]
    pub fn y(&self) -> i32 {
        self.1
    }

    #[inline(always)]
    pub fn get_rect_with_size(&self, tile_size: f32) -> skia::Rect {
        skia::Rect::from_xywh(
            self.0 as f32 * tile_size,
            self.1 as f32 * tile_size,
            tile_size,
            tile_size,
        )
    }
}

#[derive(PartialEq, Eq, Hash, Clone, Copy, Debug)]
pub struct TileRect(pub i32, pub i32, pub i32, pub i32);

#[allow(dead_code)]
impl TileRect {
    pub fn empty() -> Self {
        Self(0, 0, 0, 0)
    }

    #[inline(always)]
    pub fn is_degenerate(&self) -> bool {
        self.left() > self.right() || self.top() > self.bottom()
    }

    #[inline(always)]
    pub fn len(&self) -> i32 {
        (self.width() + 1) * (self.height() + 1)
    }

    #[inline(always)]
    pub fn x1(&self) -> i32 {
        self.0
    }

    #[inline(always)]
    pub fn y1(&self) -> i32 {
        self.1
    }

    #[inline(always)]
    pub fn x2(&self) -> i32 {
        self.2
    }

    #[inline(always)]
    pub fn y2(&self) -> i32 {
        self.3
    }

    #[inline(always)]
    pub fn left(&self) -> i32 {
        self.0
    }

    #[inline(always)]
    pub fn top(&self) -> i32 {
        self.1
    }

    #[inline(always)]
    pub fn right(&self) -> i32 {
        self.2
    }

    #[inline(always)]
    pub fn bottom(&self) -> i32 {
        self.3
    }

    /// Inclusive tile count on X (matches `contains`: both `x1` and `x2` are included).
    #[inline(always)]
    pub fn columns(&self) -> i32 {
        self.x2() - self.x1() + 1
    }

    /// Inclusive tile count on Y (matches `contains`: both `y1` and `y2` are included).
    #[inline(always)]
    pub fn rows(&self) -> i32 {
        self.y2() - self.y1() + 1
    }

    #[inline(always)]
    pub fn width(&self) -> i32 {
        self.x2() - self.x1()
    }

    #[inline(always)]
    pub fn height(&self) -> i32 {
        self.y2() - self.y1()
    }

    #[inline(always)]
    pub fn contains(&self, tile: &Tile) -> bool {
        tile.x() >= self.left()
            && tile.y() >= self.top()
            && tile.x() <= self.right()
            && tile.y() <= self.bottom()
    }

    pub fn iter(self, inclusive: bool) -> TileRectIter {
        TileRectIter::new(self, inclusive)
    }
}

#[allow(dead_code)]
pub struct TileRectIter {
    rect: TileRect,
    inclusive: bool,
    index: i32,
    total: i32,
}

impl TileRectIter {
    fn new(rect: TileRect, inclusive: bool) -> Self {
        let width = rect.width() + if inclusive { 1 } else { 0 };
        let height = rect.height() + if inclusive { 1 } else { 0 };
        Self {
            rect,
            inclusive,
            index: 0,
            total: width * height,
        }
    }
}

impl Iterator for TileRectIter {
    type Item = Tile;
    fn next(&mut self) -> Option<Self::Item> {
        if self.index >= self.total {
            return None;
        }

        let width = self.rect.width() + if self.inclusive { 1 } else { 0 };

        let x = self.rect.left() + self.index % width;
        let y = self.rect.top() + self.index / width;

        self.index += 1;

        Some(Tile::from(x, y))
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
    pub fn new_with_interest(viewbox: &Viewbox, interest: i32) -> Self {
        Self {
            visible_rect: get_tiles_for_viewbox(viewbox),
            interest_rect: get_tiles_for_viewbox_with_interest(viewbox, interest),
            interest,
            center: get_tile_center_for_viewbox(viewbox),
        }
    }

    pub fn update(&mut self, viewbox: &Viewbox) {
        self.visible_rect = get_tiles_for_viewbox(viewbox);
        self.interest_rect = get_tiles_for_viewbox_with_interest(viewbox, self.interest);
        self.center = get_tile_center_for_viewbox(viewbox);
    }

    pub fn set_interest(&mut self, interest: i32) {
        self.interest = interest;
    }

    pub fn is_visible(&self, tile: &Tile) -> bool {
        // TO CHECK self.interest_rect.contains(tile)
        self.visible_rect.contains(tile)
    }
}

pub const TILE_SIZE: f32 = 512.;

#[inline(always)]
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

pub fn get_tiles_for_viewbox(viewbox: &Viewbox) -> TileRect {
    let tile_size = get_tile_size(viewbox.get_scale());
    get_tiles_for_rect(viewbox.area, tile_size)
}

pub fn get_tiles_for_viewbox_with_interest(viewbox: &Viewbox, interest: i32) -> TileRect {
    let TileRect(sx, sy, ex, ey) = get_tiles_for_viewbox(viewbox);
    TileRect(sx - interest, sy - interest, ex + interest, ey + interest)
}

pub fn get_tile_center_for_viewbox(viewbox: &Viewbox) -> Tile {
    let TileRect(sx, sy, ex, ey) = get_tiles_for_viewbox(viewbox);
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

// This structure is useful to keep all the shape uuids by shape id.
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

    pub fn is_empty_at(&self, tile: Tile) -> bool {
        if let Some(uuids) = self.grid.get(&tile) {
            return uuids.is_empty();
        }
        true
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
const VIEWPORT_SPIRAL_DEFAULT_CAPACITY: usize = VIEWPORT_DEFAULT_CAPACITY;

/// Cached spiral of tile offsets for a given grid size.
///
/// Offsets are centered at (0,0) and must be translated by the desired origin/center tile.
#[derive(Debug, Default)]
pub struct TileSpiral {
    offsets: Vec<Tile>,
    columns: usize,
    rows: usize,
}

impl TileSpiral {
    pub fn new() -> Self {
        Self {
            offsets: Vec::with_capacity(VIEWPORT_SPIRAL_DEFAULT_CAPACITY),
            columns: 0,
            rows: 0,
        }
    }

    #[inline]
    pub fn iter(&self) -> std::slice::Iter<'_, Tile> {
        self.offsets.iter()
    }

    /// Ensure the spiral offsets match the given grid size.
    ///
    /// This regenerates offsets whenever the size changes (grow or shrink) so callers
    /// don't accidentally reuse a spiral built for a previous viewport.
    pub fn ensure(&mut self, columns: usize, rows: usize) {
        if self.columns == columns && self.rows == rows {
            return;
        }
        self.columns = columns;
        self.rows = rows;

        let total = columns.saturating_mul(rows);
        self.offsets.clear();
        self.offsets.reserve(total);

        if total == 0 {
            return;
        }

        // Generate tiles in spiral order from center (same algorithm as before).
        let mut cx = 0;
        let mut cy = 0;

        let ratio = (columns as f32 / rows as f32).ceil() as i32;

        let mut direction_current = 0;
        let mut direction_total_x = ratio;
        let mut direction_total_y = 1;
        let mut direction = 0;

        self.offsets.push(Tile(cx, cy));
        while self.offsets.len() < total {
            match direction {
                0 => cx += 1,
                1 => cy += 1,
                2 => cx -= 1,
                3 => cy -= 1,
                _ => unreachable!("Invalid direction"),
            }

            self.offsets.push(Tile(cx, cy));

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
        }

        self.offsets.reverse();
    }
}

// This structure keeps the list of tiles that are in the pending list, the
// ones that are going to be rendered.
pub struct PendingTiles {
    pub list: Vec<Tile>,
    pub spiral: TileSpiral,
    pub spiral_rect: TileRect,
    pub visible_cached: Vec<Tile>,
    pub visible_uncached: Vec<Tile>,
    pub interest_cached: Vec<Tile>,
    pub interest_uncached: Vec<Tile>,
}

impl PendingTiles {
    pub fn new() -> Self {
        Self {
            list: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            spiral: TileSpiral::new(),
            spiral_rect: TileRect::empty(),
            visible_cached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            visible_uncached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            interest_cached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            interest_uncached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
        }
    }

    pub fn update(&mut self, tile_viewbox: &TileViewbox, surfaces: &Surfaces, only_visible: bool) {
        self.list.clear();

        // During interactive transform, skip the interest-area ring
        // entirely — the user is dragging, every rAF is on the critical
        // path, and pre-rendering tiles outside the viewport is wasted
        // work that just gets evicted on the next pointer move. The ring
        // is repopulated naturally on gesture end / on idle rAFs.
        let spiral_rect = if only_visible {
            &tile_viewbox.visible_rect
        } else {
            &tile_viewbox.interest_rect
        };

        self.spiral_rect = *spiral_rect;

        // We do not regenerate spiral if the spiral_rect
        // doesn't change. The spiral_rect is based on the
        // viewbox so, if the viewbox doesn't change
        // the spiral should not change.
        let columns = spiral_rect.columns();
        let rows = spiral_rect.rows();

        self.spiral.ensure(columns as usize, rows as usize);

        // Partition tiles into 4 priority groups (highest priority = processed last due to pop()):
        // 1. visible + cached (fastest - just blit from cache)
        // 2. visible + uncached (user sees these, render next)
        // 3. interest + cached (pre-rendered area, blit from cache)
        // 4. interest + uncached (lowest priority - background pre-render)
        self.visible_cached.clear();
        self.visible_uncached.clear();
        self.interest_cached.clear();
        self.interest_uncached.clear();

        // Compute the scheduling center explicitly (inclusive range).
        // This avoids relying on `TileRect::center_x/center_y` semantics, which may be used
        // elsewhere with different expectations.
        let center_tile = Tile(
            (spiral_rect.x1() + spiral_rect.x2()) / 2,
            (spiral_rect.y1() + spiral_rect.y2()) / 2,
        );
        for spiral_tile in self.spiral.iter() {
            let tile = Tile(spiral_tile.0 + center_tile.0, spiral_tile.1 + center_tile.1);
            let is_visible = tile_viewbox.visible_rect.contains(&tile);
            let is_cached = surfaces.has_cached_tile_surface(tile);

            match (is_visible, is_cached) {
                (true, true) => self.visible_cached.push(tile),
                (true, false) => self.visible_uncached.push(tile),
                (false, true) => self.interest_cached.push(tile),
                (false, false) => self.interest_uncached.push(tile),
            }
        }

        // Build final list with lowest priority first (they get popped last)
        // Order: interest_uncached, interest_cached, visible_uncached, visible_cached
        self.list.extend(self.interest_uncached.iter());
        self.list.extend(self.interest_cached.iter());
        self.list.extend(self.visible_uncached.iter());
        self.list.extend(self.visible_cached.iter());
    }

    pub fn pop(&mut self) -> Option<Tile> {
        self.list.pop()
    }
}
