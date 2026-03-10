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
        self.grid.entry(tile).or_default();
        self.index.entry(shape_id).or_default();

        let tile_set = self.grid.get_mut(&tile).unwrap();
        tile_set.insert(shape_id);

        let index_set = self.index.get_mut(&shape_id).unwrap();
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
