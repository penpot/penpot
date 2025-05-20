use crate::uuid::Uuid;
use crate::view::Viewbox;
use indexmap::IndexSet;
use skia_safe as skia;
use std::collections::{HashMap, HashSet};

#[derive(PartialEq, Eq, Hash, Clone, Copy)]
pub struct Tile(pub i32, pub i32);

#[derive(PartialEq, Eq, Hash, Clone, Copy)]
pub struct TileRect(pub i32, pub i32, pub i32, pub i32);

impl TileRect {
    pub fn contains(&self, tile: &Tile) -> bool {
        tile.0 >= self.0 && tile.1 >= self.1 && tile.0 <= self.2 && tile.1 <= self.3
    }
}

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
        self.visible_rect.contains(tile)
    }
}

pub type TileWithDistance = (i32, i32, i32);

pub const TILE_SIZE: f32 = 512.;

// @see https://en.wikipedia.org/wiki/Taxicab_geometry
pub fn manhattan_distance(a: Tile, b: Tile) -> i32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs()
}

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
    grid: HashMap<Tile, IndexSet<Uuid>>,
    index: HashMap<Uuid, HashSet<Tile>>,
}

impl TileHashMap {
    pub fn new() -> Self {
        TileHashMap {
            grid: HashMap::new(),
            index: HashMap::new(),
        }
    }

    pub fn get_shapes_at(&mut self, tile: Tile) -> Option<&IndexSet<Uuid>> {
        self.grid.get(&tile)
    }

    pub fn remove_shape_at(&mut self, tile: Tile, id: Uuid) {
        if let Some(shapes) = self.grid.get_mut(&tile) {
            shapes.shift_remove(&id);
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
