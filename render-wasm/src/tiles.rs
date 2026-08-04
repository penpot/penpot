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

    #[inline(always)]
    pub fn get_rect_with_offset(&self, offset: &skia::Point, tile_size_px: f32) -> skia::Rect {
        skia::Rect::from_xywh(
            self.0 as f32 * tile_size_px - offset.x,
            self.1 as f32 * tile_size_px - offset.y,
            tile_size_px,
            tile_size_px,
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

/// Base tile texture size at DPR=1 (device pixels).
pub const TILE_SIZE_BASE: f32 = 512.;

/// Alias for [`TILE_SIZE_BASE`]. Prefer [`device_tile_size_px`] for layout size.
pub const TILE_SIZE: f32 = TILE_SIZE_BASE;

/// Device-pixel coverage of one tile on the backbuffer at the given view DPR.
/// At DPR=2 this is 1024 — used for placing sprites, not necessarily for
/// rasterizing them (see content-quality / interactive LOD).
#[inline(always)]
pub fn device_tile_size_px(dpr: f32) -> f32 {
    (TILE_SIZE_BASE * dpr.max(0.01)).round().max(1.0)
}

#[inline(always)]
pub fn device_tile_size_px_i32(dpr: f32) -> i32 {
    device_tile_size_px(dpr) as i32
}

/// Alias kept for call sites that mean "full-quality raster size == device coverage".
#[inline(always)]
pub fn tile_size_px(dpr: f32) -> f32 {
    device_tile_size_px(dpr)
}

#[inline(always)]
pub fn tile_size_px_i32(dpr: f32) -> i32 {
    device_tile_size_px_i32(dpr)
}

#[inline(always)]
pub fn get_tile_dimensions(dpr: f32) -> skia::ISize {
    let s = device_tile_size_px_i32(dpr);
    (s, s).into()
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
    let tile_size = get_tile_size(viewbox.get_scale(), viewbox.dpr);
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

pub fn get_tile_pos(Tile(x, y): Tile, scale: f32, dpr: f32) -> (f32, f32) {
    let ts = get_tile_size(scale, dpr);
    (x as f32 * ts, y as f32 * ts)
}

/// World/document size of one tile at `scale = zoom * dpr`.
/// Always `BASE / zoom` so viewport tile *count* matches DPR=1 regardless of
/// the raster tile texture size used for LOD.
pub fn get_tile_size(scale: f32, dpr: f32) -> f32 {
    let zoom = (scale / dpr.max(0.01)).max(1e-6);
    TILE_SIZE_BASE / zoom
}

pub fn get_tile_rect(tile: Tile, scale: f32, dpr: f32) -> skia::Rect {
    let (tx, ty) = get_tile_pos(tile, scale, dpr);
    let ts = get_tile_size(scale, dpr);
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

// This structure keeps the list of tiles that are in the pending list, the
// ones that are going to be rendered.
pub struct PendingTiles {
    pub list: Vec<Tile>,
    pub tile_order: Vec<(i32, Tile)>,
    pub tile_rect: TileRect,
    pub visible_cached: Vec<Tile>,
    pub visible_uncached: Vec<Tile>,
    pub interest_cached: Vec<Tile>,
    pub interest_uncached: Vec<Tile>,
    /// Interest-ring tiles deferred until after the viewport has been presented.
    deferred_interest: Vec<Tile>,
}

impl PendingTiles {
    pub fn new() -> Self {
        Self {
            list: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            tile_order: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            tile_rect: TileRect::empty(),
            visible_cached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            visible_uncached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            interest_cached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            interest_uncached: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
            deferred_interest: Vec::with_capacity(VIEWPORT_DEFAULT_CAPACITY),
        }
    }

    pub fn update(&mut self, tile_viewbox: &TileViewbox, surfaces: &Surfaces, only_visible: bool) {
        self.list.clear();
        self.deferred_interest.clear();

        // During interactive transform / soft HiDPI settle, skip the interest
        // ring — every rAF is on the critical path. The ring is repopulated on
        // gesture end or the full-quality upgrade pass.
        let tile_rect = if only_visible {
            &tile_viewbox.visible_rect
        } else {
            &tile_viewbox.interest_rect
        };

        self.tile_rect = *tile_rect;

        // Partition tiles into 4 priority groups (highest priority = processed last due to pop()):
        // 1. visible + cached (fastest - just blit from cache)
        // 2. visible + uncached (user sees these, render next)
        // 3. interest + cached (pre-rendered area, blit from cache)
        // 4. interest + uncached (lowest priority - background pre-render)
        self.visible_cached.clear();
        self.visible_uncached.clear();
        self.interest_cached.clear();
        self.interest_uncached.clear();

        // Enumerate every tile in `tile_rect`, ordered by distance from the
        // rect center.
        let center_x = (tile_rect.x1() + tile_rect.x2()) / 2;
        let center_y = (tile_rect.y1() + tile_rect.y2()) / 2;

        self.tile_order.clear();

        for tile in tile_rect.iter(true) {
            let dx = tile.x() - center_x;
            let dy = tile.y() - center_y;
            self.tile_order.push((dx * dx + dy * dy, tile));
        }

        // Farthest first, since we use pop() to process the tiles
        // in order of priority (closest first)
        self.tile_order.sort_unstable_by(|a, b| b.0.cmp(&a.0));

        for (_, tile) in self.tile_order.iter() {
            let tile = *tile;
            let is_visible = tile_viewbox.visible_rect.contains(&tile);
            // Interest tiles that were drawn but could not get an atlas slot must
            // not be re-queued (would loop forever with DPR-scaled 16-slot atlases).
            // Once they become visible, `has` is false until they get a real slot.
            let is_cached = surfaces.has_cached_tile_surface(tile)
                || (!is_visible && surfaces.was_rendered_without_atlas_slot(tile));

            match (is_visible, is_cached) {
                (true, true) => self.visible_cached.push(tile),
                (true, false) => self.visible_uncached.push(tile),
                (false, true) => self.interest_cached.push(tile),
                (false, false) => self.interest_uncached.push(tile),
            }
        }

        // Visible tiles first. Interest-ring work is deferred so we can present
        // as soon as the viewport is ready (see `promote_deferred_interest`).
        // Interactive/`only_visible` already excludes the ring from `tile_rect`.
        if only_visible {
            self.list.extend(self.visible_uncached.iter());
            self.list.extend(self.visible_cached.iter());
        } else {
            self.deferred_interest
                .extend(self.interest_uncached.iter());
            self.deferred_interest.extend(self.interest_cached.iter());
            self.list.extend(self.visible_uncached.iter());
            self.list.extend(self.visible_cached.iter());
        }
    }

    /// Move deferred interest-ring tiles onto the pending list.
    /// Returns true when there is interest work left to do.
    pub fn promote_deferred_interest(&mut self) -> bool {
        if self.deferred_interest.is_empty() {
            return false;
        }
        self.list.append(&mut self.deferred_interest);
        true
    }

    pub fn has_deferred_interest(&self) -> bool {
        !self.deferred_interest.is_empty()
    }

    pub fn pop(&mut self) -> Option<Tile> {
        self.list.pop()
    }
}
